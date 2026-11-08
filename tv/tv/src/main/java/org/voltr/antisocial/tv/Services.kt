package org.voltr.antisocial.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.Api
import org.voltr.antisocial.core.Status
import org.voltr.antisocial.core.YouTube
import org.voltr.antisocial.playback.hasHardwareAv1
import org.voltr.antisocial.youtube.NewPipeYouTube
import org.voltr.antisocial.core.Budget as BudgetModel

/** What every screen talks to: the anti-social server, YouTube, and the budget. */
class Services(context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** Outlives screens, so a last check-in can finish after the player closes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val api = Api(BuildConfig.SERVER_URL, prefs.getString(TOKEN, null) ?: BuildConfig.DEBUG_TOKEN.ifEmpty { null })
    val youtube: YouTube by lazy { NewPipeYouTube(av1 = hasHardwareAv1()) }
    val budget = Budget(BudgetModel(api, scope), scope)

    val signedIn get() = api.token != null

    fun signIn(token: String) {
        prefs.edit().putString(TOKEN, token).apply()
        api.token = token
    }

    fun signOut() {
        prefs.edit().remove(TOKEN).apply()
        api.token = null
    }

    private companion object {
        const val TOKEN = "token"
    }
}

/** core's Budget as Compose state, so screens recompose when it changes. */
class Budget(private val model: BudgetModel, scope: CoroutineScope) {
    var status by mutableStateOf<Status?>(null)
        private set
    var serverDown by mutableStateOf(false)
        private set
    var signedOut by mutableStateOf(false)
        private set
    var sessionError by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch { model.status.collect { status = it } }
        scope.launch { model.serverDown.collect { serverDown = it } }
        scope.launch { model.signedOut.collect { signedOut = it } }
        scope.launch { model.sessionError.collect { sessionError = it } }
    }

    suspend fun refresh() = model.refresh()

    fun update(status: Status) = model.update(status)

    fun startSession(videos: Int) = model.startSession(videos)
}
