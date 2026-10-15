package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.sign

// nanoMuse: one transient offset belongs to the whole message, including virtual Markdown rows.
internal data class NmSwipeReplyTarget(
    val messageId: String,
    val rowKey: String,
    val bounds: Rect,
    val point: Offset = bounds.center,
)

@Stable
internal class NmSwipeToReplyState(private val thresholdPx: Float) {
    var target by mutableStateOf<NmSwipeReplyTarget?>(null)
        private set
    private var distancePx by mutableFloatStateOf(0f)
    private var settling: Job? = null
    val translationPx: Float get() = distancePx.coerceIn(0f, thresholdPx * 1.5f) * 0.4f
    val progress: Float get() = (distancePx / thresholdPx).coerceIn(0f, 1f)

    fun begin(pressed: NmSwipeReplyTarget) {
        settling?.cancel()
        target = pressed.copy(messageId = pressed.messageId.substringBefore('#'))
        distancePx = 0f
    }

    fun drag(delta: Float) {
        distancePx += delta
    }

    fun replyOnRelease(): String? = target?.messageId?.takeIf { distancePx > thresholdPx }

    fun settle(scope: CoroutineScope) {
        settling?.cancel()
        settling = scope.launch {
            animate(distancePx, 0f, animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )) { value, _ -> distancePx = value }
            target = null
        }
    }
}

// nanoMuse: only bubble surfaces claim horizontal motion; child code/table scrollers take priority.
internal fun Modifier.nmSwipeToReplyGesture(
    state: NmSwipeToReplyState,
    messageId: String,
    rowKey: String,
    enabled: Boolean,
    scope: CoroutineScope,
    onReply: (String) -> Unit,
): Modifier = composed {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val reply by rememberUpdatedState(onReply)
    val haptics = LocalHapticFeedback.current
    val replyLabel = stringResource(R.string.nm_message_reply)
    onGloballyPositioned { coordinates[0] = it }
        .then(if (enabled) Modifier.semantics {
            customActions = listOf(CustomAccessibilityAction(replyLabel) { reply(messageId); true })
        } else Modifier)
        .pointerInput(state, messageId, rowKey, enabled) {
            if (!enabled) return@pointerInput
            var firstDelta = false
            try {
                detectHorizontalDragGestures(
                    onDragStart = { point ->
                        firstDelta = true
                        coordinates[0]?.takeIf { it.isAttached }?.let { coords ->
                            state.begin(NmSwipeReplyTarget(messageId, rowKey,
                                Rect(coords.positionInWindow(),
                                    androidx.compose.ui.geometry.Size(coords.size.width.toFloat(), coords.size.height.toFloat())),
                                coords.localToWindow(point)))
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        if (change.type != PointerType.Mouse && state.target?.rowKey == rowKey) {
                            change.consume()
                            // nanoMuse: raw direction also restores slop when the first delta is exactly zero.
                            val distance = if (firstDelta) {
                                firstDelta = false
                                delta + sign(change.position.x - change.previousPosition.x) * viewConfiguration.touchSlop
                            } else delta
                            state.drag(distance)
                        }
                    },
                    onDragEnd = {
                        if (state.target?.rowKey == rowKey) {
                            val id = state.replyOnRelease()
                            state.settle(scope)
                            if (id != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                reply(id)
                            }
                        }
                    },
                    onDragCancel = { if (state.target?.rowKey == rowKey) state.settle(scope) },
                )
            } finally {
                // nanoMuse: selection/menu changes and recycled rows also release any offset.
                if (state.target?.rowKey == rowKey) state.settle(scope)
            }
        }
}

// nanoMuse: translating every row once keeps joined surfaces seamless and arrows out of measurement.
@Composable
internal fun NmSwipeToReplyRow(
    state: NmSwipeToReplyState,
    messageId: String?,
    rowKey: String,
    content: @Composable () -> Unit,
) {
    if (messageId == null) {
        content()
        return
    }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val arrowInset = with(LocalDensity.current) { 32.dp.toPx() }
    val arrowHalf = with(LocalDensity.current) { 12.dp.toPx() }
    val ink = nmChatInk()
    Box(Modifier.onGloballyPositioned { coordinates[0] = it }) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopStart) {
            Icon(
                Icons.AutoMirrored.Filled.Reply,
                contentDescription = null,
                tint = ink.copy(alpha = 0.65f),
                modifier = Modifier.size(24.dp).graphicsLayer {
                    val target = state.target
                    alpha = if (target?.rowKey == rowKey) state.progress else 0f
                    val origin = coordinates[0]?.takeIf { it.isAttached }?.positionInWindow() ?: Offset.Zero
                    translationX = ((target?.bounds?.left ?: origin.x) - origin.x - arrowInset).coerceAtLeast(0f)
                    // nanoMuse: a tall row's arrow belongs beside the finger, not its offscreen center.
                    translationY = ((target?.point?.y ?: origin.y) - origin.y - arrowHalf).coerceAtLeast(0f)
                },
            )
        }
        Box(Modifier.graphicsLayer {
            translationX = if (state.target?.messageId == messageId.substringBefore('#')) state.translationPx else 0f
        }) { content() }
    }
}
