package io.github.nanomuse.ui.feed

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import io.github.nanomuse.ui.components.NmAnimatedDialog

// nanoMuse: prompt edits apply to future posts; the editable draft belongs to this dialog.
@Composable
fun NmFeedInstructionsDialog(prompt: String, onCancel: () -> Unit, onSave: (newText: String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(prompt) }
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) Color(0xFFDDDDDD) else Color(0xFF444444), tween(180), label = "nmPromptFocus")
    NmAnimatedDialog(onDismiss = onCancel) { close ->
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.nm_feed_instructions_title), fontSize = 20.sp,
                fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.nm_feed_instructions_description), fontSize = 14.sp,
                lineHeight = 21.sp, color = Color(0xFFAAAAAA))
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp).onFocusChanged { focused = it.isFocused },
                shape = RoundedCornerShape(12.dp), textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0xFF252525),
                    unfocusedContainerColor = Color(0xFF252525), focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White, focusedBorderColor = border, unfocusedBorderColor = border,
                    cursorColor = Color.White))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { close(onCancel) }) { Text(stringResource(R.string.nm_voice_cancel), color = Color(0xFFAAAAAA)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { val value = text; close { onSave(value) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Text(stringResource(R.string.nm_feed_instructions_save))
                }
            }
        }
    }
}
