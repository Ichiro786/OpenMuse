package io.github.nanomuse.ui.chat

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.settings.KEY_NM_HEADER_MODEL
import com.openminis.app.ui.settings.KEY_NM_SHOW_STEPS
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.settings.headerModelEnabled
import com.openminis.app.ui.settings.showStepsEnabled
import io.github.nanomuse.ui.home.MuseTones

/**
 * Passed to `ChatScreen` when it is hosted inside the home shell (`io.github.nanomuse.ui.home`).
 * It swaps the back arrow for Muse's hamburger, the kebab for a round button, and — for the
 * main chat — grows the header into Muse's big-face layout; side chats get their title instead.
 * It also switches the conversation to Muse's dress: grey bubbles for the agent, the one-row
 * composer pill, no per-message name.
 */
data class NmHomeChrome(
    val isMainChat: Boolean,
    val onOpenDrawer: () -> Unit,
)

/**
 * Whether the header shows the model rows under the name (Settings → Appearance). Observed, so
 * flipping the switch and coming back to the home applies without a relaunch.
 */
@Composable
fun rememberHeaderModelShown(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(headerModelEnabled(context)) }
    DisposableEffect(Unit) {
        val prefs = getAppearancePrefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_NM_HEADER_MODEL) state.value = p.getBoolean(KEY_NM_HEADER_MODEL, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/**
 * Whether the chat shows the agent's steps — tool pills, reasoning, the bar over the composer
 * (Settings → Appearance → Conversation; on by default, a stored false wins). Observed like the switch above.
 */
@Composable
fun rememberShowSteps(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(showStepsEnabled(context)) }
    DisposableEffect(Unit) {
        val prefs = getAppearancePrefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == KEY_NM_SHOW_STEPS) state.value = p.getBoolean(KEY_NM_SHOW_STEPS, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** nanoMuse: connected Markdown rows share one bubble; attachments keep their own frames. */
@Composable
fun NmAssistantBubble(
    enabled: Boolean,
    rawText: String,
    segment: NmBubbleSegment? = null,
    content: @Composable () -> Unit,
) {
    val bare = !enabled || (segment == null && isBareBlock(rawText))
    if (bare) {
        content()
        return
    }
    val first = segment?.first ?: true
    val last = segment?.last ?: true
    Box(modifier = Modifier.fillMaxWidth().padding(top = if (first) 2.dp else 0.dp, bottom = if (last) 2.dp else 0.dp), contentAlignment = Alignment.TopStart) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .then(if (segment != null) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(
                    topStart = if (first) 20.dp else 0.dp,
                    topEnd = if (first) 20.dp else 0.dp,
                    bottomStart = if (last) 20.dp else 0.dp,
                    bottomEnd = if (last) 20.dp else 0.dp,
                ))
                // nanoMuse: Meta AI's assistant pill — a touch off the background.
                .background(ChatColors.assistantBubble)
                .padding(start = 14.dp, end = 14.dp, top = if (first) 10.dp else 0.dp, bottom = if (last) 10.dp else 0.dp),
        ) {
            content()
        }
    }
}

private fun isBareBlock(raw: String): Boolean {
    val t = raw.trimStart()
    return t.startsWith("```") || t.startsWith("~~~") || t.startsWith("|") || t.startsWith("<")
}
