package io.github.nanomuse.ui.chat

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.R

// nanoMuse: compact mic control; the shared composer frame owns the glow behind it.
@Composable
fun NmVoiceInputCore(diameter: Dp, isRecording: Boolean, isTranscribing: Boolean,
    levels: List<Float>, enabled: Boolean, onTap: () -> Unit, wideGlow: Boolean = false) {
    val ink = nmChatInk()
    Box((if (wideGlow) Modifier.fillMaxWidth().height(diameter) else Modifier.size(diameter)),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape)
            .background(ink.copy(alpha = 0.07f))
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
