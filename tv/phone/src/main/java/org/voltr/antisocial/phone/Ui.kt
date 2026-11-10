package org.voltr.antisocial.phone

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The TV's tokens (tv/tv/.../Ui.kt), at the web frontend's phone-sized density. */
object Palette {
    val background = Color(0xFF181825)
    val surface = Color(0xFF1E1E2E)
    val raised = Color(0xFF313244)
    val text = Color(0xFFCDD6F4)
    val muted = Color(0xFFA6ADC8)
    val accent = Color(0xFFBD93F9)
    val onAccent = Color(0xFF000000)
    val warning = Color(0xFFF9E2AF)
    val problem = Color(0xFFF38BA8)
}

val Unit5: Dp = 5.dp

private val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

object Type {
    val body = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.text)
    val muted = body.copy(color = Palette.muted)
    val strong = body.copy(fontWeight = FontWeight.Bold)
    val problem = body.copy(color = Palette.problem)
    private val heading = body.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em)
    val h1 = heading.copy(fontSize = 22.sp, lineHeight = 28.sp)
    val h2 = heading.copy(fontSize = 18.sp, lineHeight = 24.sp)
}

@Composable
fun Label(text: String, style: TextStyle = Type.body, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
