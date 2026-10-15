package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// nanoMuse: neutral surfaces and actions shared by both native cards.
internal val NmCardInk = Color.White
internal val NmCardMuted = Color(0xFFA5A5A5)
internal val NmCardSurface = Color(0xFF1C1C1C)
internal val NmCardInset = Color(0xFF252525)
internal val NmCardOutline = Color(0xFF404040)

@Composable
internal fun NmAgentCardFrame(modifier: Modifier, animate: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    val scale = nmChatMotionScale()
    val entrance = remember { Animatable(if (animate && scale > 0f) 0f else 1f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(200)) }
    Surface(modifier = modifier.widthIn(max = 340.dp).fillMaxWidth().padding(vertical = 4.dp).graphicsLayer {
        alpha = entrance.value
        translationY = (1f - entrance.value) * 8.dp.toPx()
    }, shape = RoundedCornerShape(16.dp), color = NmCardSurface,
        border = BorderStroke(1.dp, NmCardOutline)) {
        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(
            handleColor = Color.White, backgroundColor = Color.White.copy(alpha = 0.25f))) {
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable
internal fun NmAgentCardHeader(icon: ImageVector, title: String,
    trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(NmCardInset, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = NmCardInk, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = NmCardInk, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun nmCardButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.White, contentColor = Color.Black,
    disabledContainerColor = Color(0xFF555555), disabledContentColor = Color(0xFFAAAAAA))
