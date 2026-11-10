package org.voltr.antisocial.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import org.voltr.antisocial.core.Budget
import org.voltr.antisocial.core.SessionState
import org.voltr.antisocial.core.Status
import org.voltr.antisocial.core.minutesText
import org.voltr.antisocial.core.videosText
import java.text.DateFormat
import java.util.Date

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
    val scrim = Color(0xE6181825)
}

val Unit5: Dp = 5.dp
val Corner = RoundedCornerShape(1.dp)

/** The smallest a touch target should be. */
val Touch: Dp = 44.dp

private val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

object Type {
    val body = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.text)
    val muted = body.copy(color = Palette.muted)
    val strong = body.copy(fontWeight = FontWeight.Bold)
    val link = body.copy(color = Palette.accent)
    val problem = body.copy(color = Palette.problem)
    private val heading = body.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em)
    val h1 = heading.copy(fontSize = 22.sp, lineHeight = 28.sp)
    val h2 = heading.copy(fontSize = 18.sp, lineHeight = 24.sp)
    val h3 = heading.copy(fontSize = 15.sp, lineHeight = 21.sp)
}

@Composable
fun Label(text: String, style: TextStyle = Type.body, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Anything a finger can press. Pressing shows as a raised fill, without ripples. */
@Composable
fun Tappable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Color.Transparent,
    pressedFill: Color = Palette.raised,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .background(if (pressed) pressedFill else fill, Corner)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    Tappable(
        onClick,
        modifier.heightIn(min = Touch),
        fill = if (primary) Palette.accent else Palette.raised,
        pressedFill = if (primary) Palette.text else Palette.surface,
        enabled = enabled,
        contentAlignment = Alignment.Center,
    ) {
        Label(
            text,
            when {
                !enabled -> Type.muted
                primary -> Type.strong.copy(color = Palette.onAccent)
                else -> Type.body
            },
            Modifier.padding(horizontal = Unit5 * 3, vertical = Unit5 * 2),
            maxLines = 1,
        )
    }
}

/** A text link, padded to a touch target. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = Type.link) {
    Tappable(onClick, modifier.heightIn(min = Touch)) {
        Label(text, style, Modifier.padding(horizontal = Unit5 * 2), maxLines = 1)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.surface, Corner)
            .border(1.dp, Palette.raised, Corner)
            .padding(Unit5 * 3),
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

/** The one line of session and budget state in the top bar. */
@Composable
fun BudgetClock(budget: Budget, modifier: Modifier = Modifier) {
    val status by budget.status.collectAsState()
    val serverDown by budget.serverDown.collectAsState()
    val current = status
    val (text, style) = when {
        serverDown || current == null -> "server unreachable" to Type.problem
        !current.canWatch -> "no time left" to Type.problem
        else -> clockText(current) to
            if (current.remainingSeconds <= 600) Type.strong.copy(color = Palette.warning) else Type.muted
    }
    Label(text, style, modifier, maxLines = 1)
}

fun clockText(status: Status): String {
    val time = minutesText(status.remainingSeconds)
    val session = status.session
    return if (status.state == SessionState.OPEN && session != null) {
        "${videosText(session.videosLeft)} · $time"
    } else {
        "$time left"
    }
}

/** A Unix time as the phone shows the time of day, in its own locale. */
fun timeOfDay(timestamp: Double): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((timestamp * 1000).toLong()))

/** What stands between you and watching: the question, the gap, or the end of the day. */
@Composable
fun SessionGate(budget: Budget, modifier: Modifier = Modifier) {
    val status by budget.status.collectAsState()
    val sessionError by budget.sessionError.collectAsState()
    val current = status
    Panel(modifier) {
        when (current?.state) {
            SessionState.CHOOSE -> {
                Label("How many videos?", Type.h1)
                Row(horizontalArrangement = Arrangement.spacedBy(Unit5 * 2)) {
                    current.sessionChoices.forEach { videos ->
                        ActionButton(videos.toString(), { budget.startSession(videos) }, Modifier.weight(1f))
                    }
                }
                Label("${minutesText(current.remainingSeconds)} left today", Type.muted)
                sessionError?.let { Label(it, Type.problem) }
            }
            SessionState.GAP -> {
                Label("Session over", Type.h1)
                current.nextSessionAt?.let { Label("The next one opens at ${timeOfDay(it)}") }
                Label("${minutesText(current.remainingSeconds)} left today", Type.muted)
            }
            SessionState.NO_TIME -> {
                Label("No time left today", Type.h1)
                Label("Your time resets at ${timeOfDay(current.resetsAt)}", Type.muted)
            }
            // A refusal can arrive just ahead of the status that explains it.
            SessionState.OPEN, null -> Label("Loading…", Type.muted)
        }
    }
}
