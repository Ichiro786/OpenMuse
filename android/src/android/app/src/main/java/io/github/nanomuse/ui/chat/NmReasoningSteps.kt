package io.github.nanomuse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

@Composable
internal fun nmChatInk(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else Color.Black

/** A small diagonal brightness wave. Completed steps have no animation driver. */
@Composable
internal fun NmReasoningDots(active: Boolean, modifier: Modifier = Modifier) {
    val motion = nmChatMotionScale() > 0f
    val phase = if (active && motion) {
        val transition = rememberInfiniteTransition(label = "nmReasoningDots")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "nmDotWave",
        )
    } else null
    val ink = nmChatInk()
    Canvas(modifier.size(20.dp)) {
        val unit = size.minDimension / 3f
        for (row in 0..2) for (column in 0..2) {
            val brightness = if (phase != null) {
                val offset = (row + column) * 0.13f
                val wave = cos((phase.value - offset) * 2f * PI.toFloat()).coerceAtLeast(0f).pow(3)
                0.18f + wave * 0.8f
            } else if (active) 0.6f else 0.24f
            drawCircle(
                color = ink.copy(alpha = brightness),
                radius = unit * 0.19f,
                center = Offset(unit * (column + 0.5f), unit * (row + 0.5f)),
            )
        }
    }
}

@Composable
fun NmReasoningWorking() {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NmReasoningDots(active = true)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.nm_reasoning_working), color = nmChatInk().copy(alpha = 0.65f), fontSize = 13.sp)
    }
}

/** One real tool step. Existing LazyColumn order stacks steps without regrouping messages. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NmReasoningSteps(
    block: AssistantBlock,
    onOpenDetail: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
) {
    val active = block.toolStatus in setOf(ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING)
    val motion = nmChatMotionScale() > 0f
    val ink = nmChatInk()
    val titleAlpha by animateFloatAsState(
        targetValue = if (active) 0.72f else 0.48f,
        animationSpec = tween(220),
        label = "nmStepTitle",
    )
    val action = nmReasoningAction(block.toolName)
    val fallback = stringResource(when (action) {
        NmReasoningAction.SEARCH -> R.string.nm_reasoning_search
        NmReasoningAction.CODE -> R.string.nm_reasoning_code
        NmReasoningAction.READ -> R.string.nm_reasoning_read
        NmReasoningAction.WRITE -> R.string.nm_reasoning_write
        NmReasoningAction.EDIT -> R.string.nm_reasoning_edit
        NmReasoningAction.IMAGE -> R.string.nm_reasoning_image
        NmReasoningAction.MEMORY_READ -> R.string.nm_reasoning_memory_read
        NmReasoningAction.MEMORY_WRITE -> R.string.nm_reasoning_memory_write
        NmReasoningAction.TOOL -> R.string.nm_reasoning_tool
    })
    val title = block.toolTitle.trim().takeIf {
        it.isNotEmpty() && it != block.toolName && !it.matches(Regex("[a-z]+(?:_[a-z]+)+"))
    } ?: fallback
    val sources = remember(block.browserURL, block.toolArgs, block.content) {
        nmReasoningSources(block.browserURL, block.toolArgs, block.content)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpenDetail, onLongClick = onLongClick)
            .padding(vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NmReasoningDots(active = active)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                color = ink.copy(alpha = titleAlpha),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val status = when (block.toolStatus) {
                ToolBlockStatus.FAILED -> R.string.nm_reasoning_failed
                ToolBlockStatus.TIMEOUT -> R.string.nm_reasoning_timeout
                ToolBlockStatus.CANCELLED -> R.string.nm_reasoning_stopped
                else -> null
            }
            if (status != null) {
                Spacer(Modifier.width(8.dp))
                Text(stringResource(status), fontSize = 11.sp, color = ink.copy(alpha = 0.65f))
            } else if (!active && block.durationMs > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = String.format(java.util.Locale.getDefault(), "%.1fs", block.durationMs / 1000.0),
                    color = ink.copy(alpha = 0.38f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (active && onStop != null) {
                IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Stop, stringResource(R.string.stop_tool), tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                }
            }
        }
        if (sources.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(start = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEachIndexed { index, source ->
                    key(block.id, source.key) {
                        val visible = remember { MutableTransitionState(!active || !motion) }
                        LaunchedEffect(active, motion) {
                            if (active && motion) delay(index * 80L)
                            visible.targetState = true
                        }
                        AnimatedVisibility(
                            visibleState = visible,
                            enter = fadeIn(tween(220)) + slideInVertically(tween(240)) { it / 4 },
                        ) {
                            Row(
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .background(ink.copy(alpha = 0.035f), RoundedCornerShape(50))
                                    .border(0.5.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(50))
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (source.kind == NmReasoningSourceKind.WEB) Icons.Default.Language else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = ink.copy(alpha = if (active) 0.6f else 0.35f),
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = source.label,
                                    fontSize = 11.sp,
                                    color = ink.copy(alpha = if (active) 0.65f else 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
