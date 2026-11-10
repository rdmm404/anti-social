package org.voltr.antisocial.tv

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import org.voltr.antisocial.core.ApiError
import org.voltr.antisocial.core.PairingStarted

/**
 * Joins this TV to the account without typing anything on the remote (ADR 0004). The TV shows
 * a code and a QR code; a signed-in phone or browser approves it on the settings page, and the
 * TV picks up a token of its own. Codes expire, so a new one replaces it every 10 minutes.
 */
@Composable
fun SignInScreen(services: Services, onSignedIn: () -> Unit) {
    var pairing by remember { mutableStateOf<PairingStarted?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val token = try {
                val started = services.api.startPairing(deviceName())
                pairing = started
                problem = null
                waitForApproval(services, started)
            } catch (e: ApiError) {
                problem = e.message
                null
            }
            if (token != null) {
                services.signIn(token)
                services.budget.refresh()
                onSignedIn()
                return@LaunchedEffect
            }
            // Expired, or the server is down: show the problem and try again shortly.
            if (problem != null) delay(RETRY_MS)
        }
    }

    Column(
        Modifier.widthIn(max = 900.dp).padding(Unit5 * 8),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 4),
    ) {
        Label("anti-social", Type.h1)
        Panel {
            val current = pairing
            if (current == null) {
                Label(problem ?: "Getting a code…", if (problem != null) Type.problem else Type.muted)
                return@Panel
            }
            val url = services.api.pairUrl(current.code)
            Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 6), verticalAlignment = Alignment.CenterVertically) {
                val matrix = remember(url) { qrCode(url) }
                QrCode(matrix, Modifier.size(QR_SIZE))
                Column(verticalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    Label("Scan this with a phone that's signed in, and approve.", Type.body)
                    Label("Or open ${services.api.pairUrl()} and enter:", Type.muted)
                    Label(current.code, Type.h1.copy(fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = 0.1.em))
                    Label(problem ?: "Waiting for approval…", if (problem != null) Type.problem else Type.muted)
                }
            }
        }
    }
}

/** Polls until the code is approved (the token), or until it expires (null). */
private suspend fun waitForApproval(services: Services, pairing: PairingStarted): String? {
    val every = pairing.pollEvery.coerceAtLeast(1) * 1000L
    while (true) {
        delay(every)
        try {
            services.api.claimPairing(pairing.secret)?.let { return it }
        } catch (e: ApiError) {
            if (e.code == 404) return null
            // A blip mid-wait doesn't lose the code; the next poll tries again.
            if (!e.unreachable) throw e
        }
    }
}

private fun deviceName(): String =
    if (Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) "Fire TV" else Build.MODEL.ifBlank { "TV" }

private fun qrCode(text: String): BitMatrix =
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )

/** Dark modules on a light square, with the quiet zone scanners need. */
@Composable
private fun QrCode(matrix: BitMatrix, modifier: Modifier) {
    Canvas(modifier.background(Palette.text)) {
        val quiet = 4
        val cell = size.minDimension / (matrix.width + quiet * 2)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(Palette.background, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private val QR_SIZE = 240.dp
private const val RETRY_MS = 5_000L
