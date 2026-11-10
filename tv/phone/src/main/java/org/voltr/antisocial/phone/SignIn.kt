package org.voltr.antisocial.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.ApiError
import org.voltr.antisocial.core.PairingStarted

sealed interface PairingState {
    data object Idle : PairingState
    data object Starting : PairingState
    /** The approval page is open in a Custom Tab, at [url]. */
    data class Waiting(val url: String) : PairingState
    data class Failed(val message: String) : PairingState
}

/**
 * Joins this phone to the account (ADR 0004). Unlike the TV it shows no code: it opens the
 * approval page in a Custom Tab, where the browser's signed-in session approves it, and claims
 * its token the way the TV does. The activity is paused behind the tab, so this polls from the
 * app's own scope.
 */
class Pairing(private val services: Services) {
    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(activity: Activity) {
        job?.cancel()
        job = services.scope.launch {
            _state.value = PairingState.Starting
            val started = try {
                services.api.startPairing(Build.MODEL.ifBlank { "Phone" })
            } catch (e: ApiError) {
                _state.value = PairingState.Failed(e.message ?: "Can't reach the anti-social server.")
                return@launch
            }
            val url = services.api.pairUrl(started.code)
            _state.value = PairingState.Waiting(url)
            openApprovalSheet(activity, url)
            val token = try {
                waitForApproval(started)
            } catch (e: ApiError) {
                _state.value = PairingState.Failed(e.message ?: "Pairing failed.")
                return@launch
            }
            if (token == null) {
                _state.value = PairingState.Failed("That approval page expired. Start again.")
                return@launch
            }
            services.signIn(token)
            services.budget.refresh()
            _state.value = PairingState.Idle
            // Back from the Custom Tab to the app. singleTask clears the tab off the top.
            services.context.startActivity(
                Intent(services.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Polls until the code is approved (the token), or until it expires (null). */
    private suspend fun waitForApproval(pairing: PairingStarted): String? {
        val every = pairing.pollEvery.coerceAtLeast(1) * 1000L
        while (true) {
            delay(every)
            try {
                services.api.claimPairing(pairing.secret)?.let { return it }
            } catch (e: ApiError) {
                if (e.code == 404) return null
                // A blip mid-wait doesn't lose the pairing; the next poll tries again.
                if (!e.unreachable) throw e
            }
        }
    }
}

private fun tabBuilder(session: CustomTabsSession? = null): CustomTabsIntent.Builder {
    val colors = CustomTabColorSchemeParams.Builder()
        .setToolbarColor(0xFF181825.toInt())
        .setNavigationBarColor(0xFF181825.toInt())
        .build()
    return (if (session != null) CustomTabsIntent.Builder(session) else CustomTabsIntent.Builder())
        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
        .setDefaultColorSchemeParams(colors)
        .setShowTitle(true)
}

/**
 * The approval page as a tall sheet over the app (a partial Custom Tab). Behind a full-screen
 * tab Android freezes the app, so it would stop polling, and it may not bring itself back to the
 * front. Under a sheet the app stays visible and does both. A browser without partial tabs gets
 * a full one, and the app picks up its token as soon as it's back in front.
 */
fun openApprovalSheet(activity: Activity, url: String) {
    val browser = CustomTabsClient.getPackageName(activity, null)
    if (browser == null) {
        openCustomTab(activity, url)
        return
    }
    val connection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            client.warmup(0)
            val session = client.newSession(null)
            val height = (activity.resources.displayMetrics.heightPixels * 0.9).toInt()
            val intent = tabBuilder(session)
                .setInitialActivityHeightPx(height, CustomTabsIntent.ACTIVITY_HEIGHT_FIXED)
                .setToolbarCornerRadiusDp(1)
                .build()
            try {
                intent.launchUrl(activity, Uri.parse(url))
            } catch (e: ActivityNotFoundException) {
                // Nothing can show the page.
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    // Bound from the application, so the binding outlives the activity behind the sheet.
    if (!CustomTabsClient.bindCustomTabsService(activity.applicationContext, browser, connection)) openCustomTab(activity, url)
}

/** A page of the anti-social server, in the browser's own session, styled like the app. */
fun openCustomTab(context: Context, url: String) {
    val intent = tabBuilder().build()
    try {
        intent.launchUrl(context, Uri.parse(url))
    } catch (e: ActivityNotFoundException) {
        // No browser at all. Nothing else can show the page.
    }
}

@Composable
fun SignInScreen(services: Services) {
    val state by services.pairing.state.collectAsState()
    val activity = LocalContext.current as Activity
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(Unit5 * 4),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 4),
    ) {
        Label("anti-social", Type.h1)
        Panel {
            Label("Pair this phone", Type.h2)
            Label(
                "The approval page opens in your browser. Sign in there if you need to, approve, " +
                    "and you're brought back here.",
                Type.muted,
            )
            when (val current = state) {
                PairingState.Idle -> ActionButton("Pair", { services.pairing.start(activity) }, primary = true)
                PairingState.Starting -> Label("Starting…", Type.muted)
                is PairingState.Waiting -> {
                    Label("Waiting for approval…", Type.muted)
                    ActionButton("Open the approval page again", { openApprovalSheet(activity, current.url) })
                }
                is PairingState.Failed -> {
                    Label(current.message, Type.problem)
                    ActionButton("Try again", { services.pairing.start(activity) }, primary = true)
                }
            }
        }
        Label(BuildConfig.SERVER_URL, Type.muted)
    }
}
