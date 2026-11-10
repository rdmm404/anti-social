package org.voltr.antisocial.phone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ draws behind the system bars regardless, so the content pads itself. The app
        // is always dark, so the bars keep light icons even when the phone is in light mode.
        val bars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        setContent { Skeleton() }
    }
}

/** Slice 1 of docs/plan-phone.md: proof that the app builds, installs and knows its server. */
@Composable
private fun Skeleton() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .safeDrawingPadding()
            .padding(Unit5 * 4),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
    ) {
        Label("anti-social", Type.h1)
        Label("Server", Type.muted)
        Label(BuildConfig.SERVER_URL)
        Label("Version ${BuildConfig.VERSION_NAME}", Type.muted)
    }
}
