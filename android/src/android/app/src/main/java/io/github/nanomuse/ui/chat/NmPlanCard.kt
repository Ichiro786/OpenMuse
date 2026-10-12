package io.github.nanomuse.ui.chat

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.R
import kotlin.math.ceil

// nanoMuse: a native plan attachment; its timer approves the plan, never bypasses risk gates.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NmPlanCard(
    plan: NmPlan,
    onApprove: (planId: String) -> Unit,
    onViewPlan: (planId: String) -> Unit,
    onAutoApprove: (planId: String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateKey: String = plan.id,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val duration = plan.autoApproveSeconds.coerceIn(0, 300) * 1000L
    val timerSaver = remember { Saver<NmPlanTimer, List<Any>>(
        save = { listOf(it.remainingMs, it.cancelled, it.submitted) },
        restore = { NmPlanTimer(it[0] as Long, it[1] as Boolean, it[2] as Boolean) }) }
    var timer by rememberSaveable(stateKey, stateSaver = timerSaver) {
        mutableStateOf(NmPlanTimer(duration))
    }
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val approve = rememberUpdatedState(onAutoApprove)
    LaunchedEffect(stateKey, enabled, timer.cancelled, timer.submitted, lifecycle) {
        if (!enabled || timer.cancelled || timer.submitted) return@LaunchedEffect
        // Cancel the frame loop directly on pause, even if composition is suspended.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = SystemClock.elapsedRealtime()
            while (timer.remainingMs > 0 && !timer.cancelled && !timer.submitted) {
                withFrameNanos { }
                val now = SystemClock.elapsedRealtime()
                timer = timer.advance(now - previous)
                previous = now
            }
            if (enabled && timer.expired) {
                timer = timer.submit()
                approve.value(plan.id)
            }
        }
    }
    val interactive = enabled && !timer.submitted
    NmAgentCardFrame(modifier, animate = enabled) {
        NmAgentCardHeader(Icons.Outlined.Checklist, plan.title, trailingAction)
        if (plan.description.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(plan.description, style = MaterialTheme.typography.bodyMedium, color = NmCardMuted)
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().background(NmCardInset, RoundedCornerShape(12.dp))
            .animateContentSize(tween(200)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Checklist, contentDescription = null, tint = NmCardMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nm_card_todos), style = MaterialTheme.typography.labelLarge,
                    color = NmCardInk, modifier = Modifier.weight(1f))
                Surface(shape = CircleShape, color = NmCardSurface) {
                    Text(java.text.NumberFormat.getIntegerInstance().format(plan.todos.size),
                        color = NmCardMuted, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            (if (expanded) plan.todos else plan.todos.take(3)).forEach { todo ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(16.dp)) {
                        drawCircle(NmCardMuted, radius = size.minDimension / 2f - 1.dp.toPx(),
                            style = Stroke(1.dp.toPx()))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(todo, style = MaterialTheme.typography.bodyMedium, color = NmCardMuted,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                }
            }
            if (plan.todos.size > 3) TextButton(onClick = {
                timer = timer.cancel()
                expanded = !expanded
            }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = null, tint = NmCardMuted,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (expanded) stringResource(R.string.nm_card_show_less)
                    else stringResource(R.string.nm_card_more, plan.todos.size - 3), color = NmCardMuted)
            }
        }
        if (enabled || timer.submitted) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val fraction = if (duration > 0L) timer.remainingMs.toFloat() / duration else 0f
                Canvas(Modifier.size(24.dp)) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(NmCardOutline, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(NmCardInk, -90f, fraction * 360f, false, Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Spacer(Modifier.width(10.dp))
                Text(when {
                    timer.submitted -> stringResource(R.string.nm_card_approved)
                    timer.cancelled -> stringResource(R.string.nm_card_auto_paused)
                    else -> stringResource(R.string.nm_card_auto_approve,
                        ceil(timer.remainingMs / 1000.0).toInt())
                }, color = NmCardMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = {
                timer = timer.cancel()
                expanded = true
                onViewPlan(plan.id)
            }, border = BorderStroke(1.dp, NmCardOutline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NmCardMuted,
                    disabledContentColor = NmCardMuted.copy(alpha = 0.5f))) {
                Text(stringResource(R.string.nm_card_view_plan))
            }
            Button(onClick = {
                if (interactive) {
                    timer = timer.submit()
                    onApprove(plan.id)
                }
            }, enabled = interactive, colors = nmCardButtonColors()) {
                Text(stringResource(R.string.nm_card_approve))
            }
        }
    }
}
