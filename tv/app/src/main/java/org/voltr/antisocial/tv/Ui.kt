package org.voltr.antisocial.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** The web frontend's tokens. A TV's 2x density makes the same numbers readable from the couch. */
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
    val scrim = Color(0xE6181825)
}

val Unit5: Dp = 5.dp
val Corner = RoundedCornerShape(1.dp)

private val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

object Type {
    val body = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.text)
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

/** Anything the remote can land on. Focus has to be unmistakable from across the room. */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Color.Transparent,
    focusedFill: Color = Palette.raised,
    focusBorder: Color = Palette.accent,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .background(if (focused) focusedFill else fill, Corner)
            .border(2.dp, if (focused) focusBorder else Color.Transparent, Corner)
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        content(focused)
    }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Focusable(
        onClick,
        modifier,
        fill = if (primary) Palette.accent else Palette.raised,
        focusedFill = if (primary) Palette.accent else Palette.raised,
        // An accent ring can't show on an accent fill.
        focusBorder = if (primary) Palette.text else Palette.accent,
    ) {
        Label(
            text,
            if (primary) Type.strong.copy(color = Palette.onAccent) else Type.body,
            Modifier.padding(horizontal = Unit5 * 3, vertical = Unit5 * 2),
            maxLines = 1,
        )
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.surface, Corner)
            .border(1.dp, Palette.raised, Corner)
            .padding(Unit5 * 4),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 2),
    ) {
        content()
    }
}

@Composable
fun BudgetMeter(status: Status) {
    val used = if (status.budgetSeconds > 0) (status.usedSeconds / status.budgetSeconds).toFloat().coerceIn(0f, 1f) else 1f
    Box(Modifier.fillMaxWidth().height(Unit5 * 2).background(Palette.raised, Corner)) {
        Box(Modifier.fillMaxWidth(used).height(Unit5 * 2).background(Palette.accent, Corner))
    }
}

/** The one line of budget state in the top bar. */
@Composable
fun BudgetClock(status: Status?, serverDown: Boolean) {
    val (text, style) = when {
        serverDown || status == null -> "server unreachable" to Type.problem
        status.unlockWaitLeft != null -> "unlock in ${status.unlockWaitLeft}s" to Type.muted
        !status.canWatch -> "no time left" to Type.problem
        status.remainingSeconds <= 600 -> "${minutesText(status.remainingSeconds)} left" to Type.strong.copy(color = Palette.warning)
        else -> "${minutesText(status.remainingSeconds)} left" to Type.muted
    }
    Label(text, style, maxLines = 1)
}

/** Shown wherever watching stops. The server decides; this only offers what it allows. */
@Composable
fun OutOfTimePanel(budget: Budget, modifier: Modifier = Modifier) {
    val status = budget.status
    val unlockFocus = remember { FocusRequester() }
    val canUnlock = status != null && status.unlockWaitLeft == null && status.unlocksLeft > 0
    LaunchedEffect(canUnlock) { if (canUnlock) unlockFocus.requestFocus() }
    Panel(modifier) {
        Label("No time left today", Type.h2)
        if (status == null) return@Panel
        Label("Your budget resets at ${timeOfDay(status.resetsAt)}", Type.muted)
        val waiting = status.unlockWaitLeft
        when {
            waiting != null -> Label("Adding ${status.unlockMinutes} min in ${waiting}s.")
            status.unlocksLeft > 0 -> Row(
                horizontalArrangement = Arrangement.spacedBy(Unit5 * 3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    "Use an unlock (${status.unlocksLeft} left)",
                    budget::unlock,
                    Modifier.focusRequester(unlockFocus),
                    primary = true,
                )
                Label("+${status.unlockMinutes} min, after a ${status.unlockWaitSeconds}s wait", Type.muted)
            }
            else -> Label("No unlocks left today.", Type.muted)
        }
        budget.unlockError?.let { Label(it, Type.problem) }
    }
}
