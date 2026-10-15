package io.github.nanomuse.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import io.github.nanomuse.ui.components.NmAnimatedDialog
import kotlinx.coroutines.delay

// nanoMuse: agent model selection and the existing speech pipeline share one animated dialog.
data class NmVoiceModel(val id: String, val label: String, val provider: String)

@Composable
fun NmAgentVoiceDialog(models: List<NmVoiceModel>, selectedId: String?, inputText: String,
    busy: Boolean, onStart: (String) -> Boolean, onDismiss: () -> Unit,
    voiceContent: @Composable (startCaptureToken: Int, requestCapture: () -> Unit) -> Unit) {
    var selection by rememberSaveable { mutableStateOf(selectedId ?: models.firstOrNull()?.id) }
    var startToken by remember { mutableIntStateOf(0) }
    var initialText by remember { mutableStateOf(inputText) }
    val state by SpeechRecognitionManager.state.collectAsState()
    val available by SpeechRecognitionManager.isAvailable.collectAsState()
    val recording = state == RecognitionState.STARTING || state == RecognitionState.RECORDING
    val processing = state == RecognitionState.FINISHING
    fun requestCapture() {
        val id = selection ?: return
        if (!busy && !recording && !processing && available && models.any { it.id == id } && onStart(id)) {
            initialText = inputText
            startToken++
        }
    }
    NmAnimatedDialog(onDismiss) { close ->
        LaunchedEffect(startToken, inputText, state) {
            if (startToken > 0 && state == RecognitionState.IDLE && inputText != initialText && inputText.isNotBlank()) {
                delay(300)
                close(onDismiss)
            }
        }
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.nm_voice_agent), style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(12.dp))
            if (models.isEmpty()) Text(stringResource(R.string.nm_voice_no_models), color = Color(0xFFAAAAAA))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(models, key = { it.id }) { model ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !recording && !processing) { selection = model.id }
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selection == model.id, enabled = !recording && !processing,
                            onClick = { selection = model.id }, colors = RadioButtonDefaults.colors(
                                selectedColor = Color.White, unselectedColor = Color(0xFF888888)))
                        Column(Modifier.weight(1f)) {
                            Text(model.label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            Text(model.provider, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA))
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF333333))
            voiceContent(startToken, ::requestCapture)
            // nanoMuse: recognition settings and the existing Read replies label stay in Agent.
            val repliesEnabled by com.openminis.app.speech.VoiceOutputState.isEnabled.collectAsState()
            val repliesMuted by com.openminis.app.speech.VoiceOutputState.isMuted.collectAsState()
            TextButton(onClick = {
                val output = com.openminis.app.speech.VoiceOutputState
                when {
                    repliesEnabled && !repliesMuted -> output.setMuted(true)
                    repliesEnabled && repliesMuted -> output.setEnabled(false)
                    else -> { output.setMuted(false); output.setEnabled(true) }
                }
            }) {
                Text(stringResource(R.string.voice_panel_read_replies),
                    color = if (repliesEnabled && !repliesMuted) Color.White else Color(0xFFAAAAAA))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { close(onDismiss) }) { Text(stringResource(R.string.nm_voice_cancel), color = Color(0xFFAAAAAA)) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = !busy && !recording && !processing && available && models.any { it.id == selection }, onClick = {
                    requestCapture()
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
                    disabledContainerColor = Color(0xFF444444), disabledContentColor = Color(0xFF888888))) {
                    Text(stringResource(R.string.nm_voice_start))
                }
            }
        }
    }
}
