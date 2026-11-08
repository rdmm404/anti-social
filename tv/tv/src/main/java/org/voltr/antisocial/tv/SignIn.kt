package org.voltr.antisocial.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.voltr.antisocial.core.ApiError

/**
 * Joins this TV to the server with its device token. Typing one on a remote is slow; pasting
 * works with `adb shell input text <token>` while the field is open.
 */
@Composable
fun SignInScreen(services: Services, onSignedIn: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val button = remember { FocusRequester() }

    fun submit() {
        val candidate = token.trim()
        keyboard?.hide()
        editing = false
        if (candidate.isEmpty() || checking) return
        checking = true
        scope.launch {
            val previous = services.api.token
            services.api.token = candidate
            try {
                services.api.status()
                services.signIn(candidate)
                services.budget.refresh()
                problem = null
                onSignedIn()
            } catch (e: ApiError) {
                services.api.token = previous
                problem = if (e.signedOut) "The server doesn't know that token." else e.message
            } finally {
                checking = false
            }
        }
    }

    Column(
        Modifier.widthIn(max = 720.dp).padding(Unit5 * 8),
        verticalArrangement = Arrangement.spacedBy(Unit5 * 4),
    ) {
        Label("anti-social", Type.h1)
        Panel {
            Label("Sign in", Type.h2)
            Label("Enter this TV's device token for ${BuildConfig.SERVER_URL}.", Type.muted)
            if (editing) {
                BasicTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    textStyle = Type.body,
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .background(Palette.raised, Corner)
                        .padding(Unit5 * 3),
                )
                LaunchedEffect(Unit) {
                    field.requestFocus()
                    keyboard?.show()
                }
            } else {
                Focusable({ editing = true }, Modifier.fillMaxWidth().focusRequester(button), fill = Palette.raised) {
                    Label(
                        if (token.isEmpty()) "Device token" else "•".repeat(token.length.coerceAtMost(40)),
                        if (token.isEmpty()) Type.muted else Type.body,
                        Modifier.padding(Unit5 * 3),
                        maxLines = 1,
                    )
                }
                ActionButton(if (checking) "Checking…" else "Sign in", { submit() }, primary = true)
                LaunchedEffect(Unit) { button.requestFocus() }
            }
            problem?.let { Label(it, Type.problem) }
        }
    }
}
