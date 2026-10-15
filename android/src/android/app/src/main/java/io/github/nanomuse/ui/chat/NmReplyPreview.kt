package io.github.nanomuse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R

// nanoMuse: the preview participates in footer measurement, including its exit animation.
@Composable
internal fun NmReplyPreview(reply: NmReplyReference?, agentName: String, onCancel: () -> Unit) {
    var lastReply by remember { mutableStateOf(reply) }
    SideEffect { if (reply != null) lastReply = reply }
    AnimatedVisibility(reply != null,
        enter = expandVertically(tween(200, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom) + fadeIn(tween(200)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { it },
        exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom) + fadeOut(tween(200)) + slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it }) {
        lastReply?.let { quoted ->
            val sender = if (quoted.role == "user") stringResource(R.string.nm_message_you) else agentName
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .background(Color(0xFF1C1C1C), RoundedCornerShape(16.dp)).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(stringResource(R.string.nm_replying_to, sender), color = Color.White,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(quoted.text.replace(Regex("\\s+"), " "), color = Color(0xFFAAAAAA),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.nm_reply_cancel), tint = Color(0xFFBBBBBB))
                }
            }
        }
    }
}

@Composable
internal fun NmReplyQuote(reply: NmReplyReference, agentName: String) {
    val sender = if (reply.role == "user") stringResource(R.string.nm_message_you) else agentName
    Row(Modifier.widthIn(max = 300.dp).background(Color(0xFF303030), RoundedCornerShape(12.dp))
        .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(2.dp).height(32.dp).background(Color.White.copy(alpha = 0.55f)))
        Column(Modifier.weight(1f, fill = false)) {
            Text(sender, color = Color.White, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold)
            Text(reply.text.replace(Regex("\\s+"), " "), color = Color(0xFFBBBBBB), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}
