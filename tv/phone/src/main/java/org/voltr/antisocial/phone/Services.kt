package org.voltr.antisocial.phone

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.voltr.antisocial.core.Api
import org.voltr.antisocial.core.Budget
import org.voltr.antisocial.core.YouTube
import org.voltr.antisocial.playback.hasHardwareAv1
import org.voltr.antisocial.youtube.NewPipeYouTube

class AntiSocialApp : Application() {
    val services by lazy { Services(this) }
}

val Context.services: Services get() = (applicationContext as AntiSocialApp).services

/**
 * What the screens and the playback service share: the anti-social server, YouTube, the budget
 * and the video that's open. One per process, so playback outlives the activity.
 */
class Services(val context: Context) {
    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** Outlives screens, so pairing goes on behind a Custom Tab and a last check-in finishes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val api = Api(BuildConfig.SERVER_URL, prefs.getString(TOKEN, null) ?: BuildConfig.DEBUG_TOKEN.ifEmpty { null })
    val youtube: YouTube by lazy { NewPipeYouTube(av1 = hasHardwareAv1()) }
    val budget = Budget(api, scope)
    val playback = PlaybackController(this)
    val pairing = Pairing(this)
    val importer = Importer(this)

    private val _signedIn = MutableStateFlow(api.token != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun signIn(token: String) {
        prefs.edit().putString(TOKEN, token).apply()
        api.token = token
        _signedIn.value = true
    }

    fun signOut() {
        playback.close()
        prefs.edit().remove(TOKEN).apply()
        api.token = null
        _signedIn.value = false
    }

    private companion object {
        const val TOKEN = "token"
    }
}
