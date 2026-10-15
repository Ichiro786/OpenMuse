package io.github.nanomuse.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// nanoMuse: capture the pressed message and its window bounds, independent of streaming rows.
data class NmMessageMenuTarget(
    val messageId: String,
    val text: String,
    val point: Offset,
    val bounds: Rect,
)

// nanoMuse: clip to the visible window and keep tall, scrolled messages anchored beside the press.
internal fun nmMessageMenuPosition(
    point: Offset,
    bounds: Rect,
    window: IntRect,
    menu: IntSize,
    margin: Int,
): IntOffset {
    val maxX = (window.right - menu.width - margin).coerceAtLeast(window.left)
    val maxY = (window.bottom - menu.height - margin).coerceAtLeast(window.top)
    val minX = minOf(window.left + margin, maxX)
    val minY = minOf(window.top + margin, maxY)
    val top = bounds.top.coerceIn(window.top.toFloat(), window.bottom.toFloat())
    val bottom = bounds.bottom.coerceIn(top, window.bottom.toFloat())
    val pressY = point.y.coerceIn(top, bottom)
    // nanoMuse: the 12dp gap plus a nearby edge stays within about 16dp of the finger.
    val edgeSlack = margin / 3f
    val anchorTop = if (pressY - top <= edgeSlack) top else pressY
    val anchorBottom = if (bottom - pressY <= edgeSlack) bottom else pressY
    val above = anchorTop - menu.height - margin
    val below = anchorBottom + margin
    val y = when {
        above >= minY -> above
        below <= maxY -> below
        anchorTop - window.top >= window.bottom - anchorBottom -> above
        else -> below
    }
    return IntOffset(
        (point.x - menu.width / 2f).roundToInt().coerceIn(minX, maxX),
        y.roundToInt().coerceIn(minY, maxY),
    )
}

private val nmQuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private val nmMoreReactions = listOf(
    "🔥", "🎉", "👏", "💯", "🤔", "🙌",
    "😊", "😎", "🤝", "💪", "👀", "✅",
    "⭐", "💡", "🚀", "🤍", "🫶", "😅",
)

// nanoMuse: focus, back and outside dismissal stay native; actions wait for the closing motion.
@Composable
internal fun NmMessageLongPressMenu(
    target: NmMessageMenuTarget,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
    canSelect: Boolean = true,
) {
    var closing by remember(target) { mutableStateOf(false) }
    var more by remember(target) { mutableStateOf(false) }
    var pending by remember(target) { mutableStateOf<(() -> Unit)?>(null) }
    val opacity = remember(target) { Animatable(0f) }
    val scale = remember(target) { Animatable(0.9f) }
    val dismiss by rememberUpdatedState(onDismiss)
    fun close(action: (() -> Unit)? = null) {
        if (!closing) {
            pending = action
            closing = true
        }
    }
    LaunchedEffect(target, closing) {
        coroutineScope {
            launch { opacity.animateTo(if (closing) 0f else 1f, tween(if (closing) 150 else 180)) }
            launch {
                scale.animateTo(if (closing) 0.9f else 1f,
                    tween(if (closing) 150 else 180, easing = if (closing) NmMenuSettle else NmMenuBloom))
            }
        }
        if (closing) {
            val action = pending
            dismiss()
            action?.invoke()
        }
    }
    // nanoMuse: the host view converts the visible display frame into the target's window space.
    val view = LocalView.current
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    var surfaceOffset by remember(target) { mutableStateOf(IntOffset.Zero) }
    var surfaceSize by remember(target) { mutableStateOf(IntSize.Zero) }
    val position = remember(target, margin, view) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val visible = android.graphics.Rect()
                view.getWindowVisibleDisplayFrame(visible)
                val screen = IntArray(2)
                val window = IntArray(2)
                view.getLocationOnScreen(screen)
                view.getLocationInWindow(window)
                visible.offset(window[0] - screen[0], window[1] - screen[1])
                return nmMessageMenuPosition(target.point, target.bounds,
                    IntRect(visible.left, visible.top, visible.right, visible.bottom),
                    popupContentSize, margin).also {
                    surfaceOffset = it
                    surfaceSize = popupContentSize
                }
            }
        }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { close() },
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(16.dp)
        // nanoMuse: the popup measures the actual surface, with room for the visible-window margins.
        Column(
            modifier = Modifier.layout { measurable, constraints ->
                val surface = measurable.measure(constraints.offset(-margin * 2, -margin * 2))
                layout(surface.width, surface.height) { surface.placeRelative(0, 0) }
            }.width(312.dp)
                .animateContentSize(tween(200, easing = NmMenuSettle))
                .graphicsLayer {
                    alpha = opacity.value
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = TransformOrigin(
                        ((target.point.x - surfaceOffset.x) / surfaceSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        ((target.point.y - surfaceOffset.y) / surfaceSize.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                }
                .clip(shape)
                .background(Color(0xFF191919))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                nmQuickReactions.forEach { emoji ->
                    NmReactionChoice(emoji, !closing) { close { onReact(emoji) } }
                }
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(enabled = !closing, role = Role.Button, onClick = { more = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.nm_reaction_more),
                        tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(18.dp))
                }
            }
            if (more) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    nmMoreReactions.chunked(6).forEach { emojis ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            emojis.forEach { emoji ->
                                NmReactionChoice(emoji, !closing) { close { onReact(emoji) } }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth()) {
                listOf(
                    Triple(R.string.nm_message_reply, Icons.AutoMirrored.Filled.Reply, onReply),
                    Triple(R.string.nm_message_copy, Icons.Default.ContentCopy, onCopy),
                    Triple(R.string.nm_message_select, Icons.Default.SelectAll, onSelect),
                ).forEach { (label, icon, action) ->
                    val permitted = label != R.string.nm_message_select || canSelect
                    val foreground = Color(0xFFE0E0E0).copy(alpha = if (permitted) 1f else 0.35f)
                    Row(
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                            .clickable(enabled = !closing && permitted, role = Role.Button, onClick = { close(action) })
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = foreground,
                            modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(label), color = foreground, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
        }
    }
}

@Composable
private fun NmReactionChoice(emoji: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // nanoMuse: preserve the system emoji colors while menu chrome stays neutral.
        Text(emoji, fontSize = 22.sp)
    }
}
