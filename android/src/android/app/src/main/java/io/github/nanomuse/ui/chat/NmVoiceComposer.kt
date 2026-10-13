package io.github.nanomuse.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import io.github.nanomuse.ui.components.NmMonochrome

// nanoMuse: one bottom-edge glow behind the whole input, never inside the mic hit target.
@Composable
fun NmVoiceComposerFrame(active: Boolean, modifier: Modifier = Modifier,
    monochrome: Boolean = false, animate: Boolean = false, content: @Composable () -> Unit) {
    val frame: @Composable () -> Unit = {
        val state by SpeechRecognitionManager.state.collectAsState()
        val levels by SpeechRecognitionManager.audioLevels.collectAsState()
        var captured by remember { mutableStateOf(false) }
        val working = active && state != RecognitionState.IDLE
        SideEffect { if (working) captured = true }
        val phase = when {
            !active -> NmVoicePhase.Idle
            state == RecognitionState.STARTING || state == RecognitionState.RECORDING -> NmVoicePhase.Listening
            state == RecognitionState.FINISHING -> NmVoicePhase.Thinking
            captured -> NmVoicePhase.Done
            else -> NmVoicePhase.Idle
        }
        // nanoMuse: bottom anchoring keeps the controls in place as the same surface grows.
        Box(modifier.then(if (animate) Modifier.animateContentSize(nmComposerSpring(),
            alignment = Alignment.BottomCenter) else Modifier)) {
            NmVoiceGlow(phase, levels.lastOrNull() ?: 0f, Modifier.matchParentSize())
            content()
        }
    }
    if (monochrome || active) NmMonochrome(frame) else frame()
}

// nanoMuse: reference-style transcript stage; existing ASR callbacks still own the text.
@Composable
fun NmVoiceTranscript(text: String, recording: Boolean, processing: Boolean, error: String?,
    onTextChange: (String) -> Unit, modifier: Modifier = Modifier, editor: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp)
        .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.Center) {
        if (editor != null) editor() else BasicTextField(value = text, onValueChange = onTextChange, readOnly = recording || processing,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 23.sp),
            cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth()
                .weight(1f, fill = false).verticalScroll(rememberScrollState()),
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) Text(stringResource(R.string.voice_panel_tip_tap_to_transcribe),
                        color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodyLarge)
                    field()
                }
            })
        if (error != null) Text(error, color = Color(0xFFBBBBBB),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

// nanoMuse: small neutral controls match the reference; labels remain localized.
@Composable
fun NmVoiceToolButton(label: String, onClick: () -> Unit, dropdown: Boolean = false) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
        .clickable(onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFCCCCCC), fontSize = 13.sp)
        if (dropdown) Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
            tint = Color(0xFFAAAAAA), modifier = Modifier.padding(start = 3.dp).size(14.dp))
    }
}

@Composable
fun NmVoiceRoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color(0xFFDDDDDD), modifier = Modifier.size(20.dp))
    }
}
