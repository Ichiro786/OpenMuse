package io.github.nanomuse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R

// nanoMuse: the glow replaces only the old disc/waveform; microphone hit targets stay unchanged.
@Composable
fun NmVoiceInputCore(diameter: Dp, isRecording: Boolean, isTranscribing: Boolean,
    levels: List<Float>, enabled: Boolean, onTap: () -> Unit, wideGlow: Boolean = false) {
    val ink = nmChatInk()
    var captured by remember { mutableStateOf(false) }
    SideEffect { if (isRecording || isTranscribing) captured = true }
    val phase = when {
        isTranscribing -> NmVoicePhase.Thinking
        isRecording -> NmVoicePhase.Listening
        captured -> NmVoicePhase.Done
        else -> NmVoicePhase.Idle
    }
    Box((if (wideGlow) Modifier.fillMaxWidth().height(diameter) else Modifier.size(diameter)).clipToBounds(),
        contentAlignment = Alignment.Center) {
        NmVoiceGlow(phase, levels.lastOrNull() ?: 0f, Modifier.matchParentSize())
        Box(Modifier.size(diameter).clip(CircleShape)
            .background(ink.copy(alpha = if (isRecording || isTranscribing) 0.025f else 0.06f))
            .border(1.dp, ink.copy(alpha = 0.15f), CircleShape)
            .clickable(enabled = enabled, onClick = onTap), contentAlignment = Alignment.Center) {
            Icon(when {
                isTranscribing -> Icons.Outlined.Close
                isRecording -> Icons.Outlined.Stop
                else -> Icons.Outlined.Mic
            }, contentDescription = stringResource(if (isTranscribing) R.string.voice_panel_cancel_transcription
                else R.string.voice_panel_toggle_recording), tint = ink, modifier = Modifier.size(24.dp))
        }
    }
}
