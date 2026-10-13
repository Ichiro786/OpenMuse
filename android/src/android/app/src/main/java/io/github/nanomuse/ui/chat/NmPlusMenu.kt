package io.github.nanomuse.ui.chat

import android.app.ActivityManager
import android.app.Activity
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openminis.app.R
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import io.github.nanomuse.ui.components.NmMonochrome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// nanoMuse: the popup is a sibling of its Haze source in the SAME window, retaining IME focus.
enum class NmPlusAction { Photos, Camera, Files, Skills }

@Stable
class NmPlusMenuState {
    var expanded by mutableStateOf(false); private set
    var present by mutableStateOf(false); private set
    var anchorBounds by mutableStateOf(Rect.Zero)
    var surfaceBounds by mutableStateOf(Rect.Zero)
    var rootPosition by mutableStateOf(Offset.Zero)
    private var pending: NmPlusAction? = null
    fun toggle() { if (expanded) close() else { pending = null; present = true; expanded = true } }
    fun close(action: NmPlusAction? = null) { if (expanded) { pending = action; expanded = false } }
    internal fun finishClose(): NmPlusAction? {
        if (expanded) return null
        present = false
        return pending.also { pending = null }
    }
}

// nanoMuse: opening/dismissing the menu never clears focus or creates a focusable popup window.
internal fun Modifier.nmPlusOutsideTap(state: NmPlusMenuState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val point = down.position + state.rootPosition
        if (state.expanded && !state.anchorBounds.contains(point) && !state.surfaceBounds.contains(point)) {
            state.close()
            down.consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

internal fun nmPlusMenuPosition(anchor: IntRect, viewport: IntSize, menu: IntSize, gap: Int): IntOffset =
    IntOffset(anchor.left.coerceIn(0, (viewport.width - menu.width).coerceAtLeast(0)),
        (anchor.top - gap - menu.height).coerceIn(0, (viewport.height - menu.height).coerceAtLeast(0)))

@Composable
internal fun rememberNmMenuBlur(present: Boolean): Boolean {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = remember(context) {
        var owner = context
        while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
        (owner as? Activity)?.window
    }
    val capable = remember(context) { HazeDefaults.blurEnabled() &&
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true }
    var permitted by remember(capable) { mutableStateOf(capable) }
    // nanoMuse: passive CPU/GPU frame metrics; no continuous timer or redraw loop when idle.
    DisposableEffect(present, permitted, window, lifecycle) {
        if (!present || !permitted || window == null) return@DisposableEffect onDispose { }
        val hz = (view.display?.refreshRate ?: 60f).coerceAtLeast(30f)
        val budget = NmMenuFrameBudget((1_000_000_000.0 / hz).toLong())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                budget.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION))) permitted = false
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        onDispose { window.removeOnFrameMetricsAvailableListener(listener) }
    }
    return permitted
}

@Composable
fun NmPlusMenu(state: NmPlusMenuState, hazeState: HazeState, blurEnabled: Boolean,
    onAction: (NmPlusAction) -> Unit, modifier: Modifier = Modifier) {
    val opacity = remember { Animatable(0f) }
    val scale = remember { Animatable(0.8f) }
    val action = rememberUpdatedState(onAction)
    BackHandler(enabled = state.expanded) { state.close() }
    LaunchedEffect(state.expanded) {
        if (state.expanded) coroutineScope {
            launch { opacity.animateTo(1f, tween(200)) }
            launch { scale.animateTo(1f, tween(200, easing = NmMenuBloom)) }
        } else if (state.present) {
            coroutineScope {
                launch { opacity.animateTo(0f, tween(150)) }
                launch { scale.animateTo(0.85f, tween(150, easing = NmMenuSettle)) }
            }
            state.finishClose()?.let { action.value(it) }
        } else {
            opacity.snapTo(0f); scale.snapTo(0.8f)
        }
    }
    if (!state.present) return
    val density = LocalDensity.current
    NmMonochrome {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val anchor = state.anchorBounds.translate(-state.rootPosition)
            val gap = with(density) { 8.dp.roundToPx() }
            val width = minOf(248.dp, maxWidth - 16.dp).coerceAtLeast(1.dp)
            val maxMenuHeight = with(density) { (anchor.top - gap - 8.dp.toPx()).coerceAtLeast(1f).toDp() }
                .coerceAtMost(maxHeight)
            val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
            var measured by remember { mutableStateOf(IntSize.Zero) }
            val menuOffset = nmPlusMenuPosition(IntRect(anchor.left.roundToInt(), anchor.top.roundToInt(),
                anchor.right.roundToInt(), anchor.bottom.roundToInt()), viewport, measured, gap)
            val shape = RoundedCornerShape(20.dp)
            val title = stringResource(R.string.nm_plus_label)
            Column(Modifier.absoluteOffset { menuOffset }.width(width).heightIn(max = maxMenuHeight)
                .graphicsLayer {
                    alpha = opacity.value; scaleX = scale.value; scaleY = scale.value
                    transformOrigin = TransformOrigin(0f, 1f)
                }.onGloballyPositioned { measured = it.size; state.surfaceBounds = it.boundsInWindow() }.clip(shape)
                .then(if (blurEnabled) Modifier.hazeEffect(hazeState) {
                    style = HazeStyle(backgroundColor = Color(0xFF101010), blurRadius = 24.dp,
                        noiseFactor = 0.025f, tints = listOf(HazeTint(Color.Black.copy(alpha = 0.7f))))
                    this.blurEnabled = true
                } else Modifier.background(Color(0xFF101010).copy(alpha = 0.7f)))
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
                .semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                NmPlusRow(0, state, Icons.Outlined.Image, R.string.nm_plus_photos, NmPlusAction.Photos)
                NmPlusRow(1, state, Icons.Outlined.CameraAlt, R.string.nm_plus_camera, NmPlusAction.Camera)
                NmPlusRow(2, state, Icons.Outlined.Description, R.string.nm_plus_files, NmPlusAction.Files)
                NmPlusRow(3, state, Icons.Outlined.AutoAwesome, R.string.nm_voice_skills, NmPlusAction.Skills)
            }
        }
    }
}

@Composable
private fun NmPlusRow(index: Int, state: NmPlusMenuState, icon: ImageVector, label: Int, action: NmPlusAction) {
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(8f) }
    val density = LocalDensity.current
    LaunchedEffect(state.expanded) {
        if (state.expanded) {
            delay(index * 40L)
            coroutineScope {
                launch { alpha.animateTo(1f, tween(160)) }
                launch { rise.animateTo(0f, tween(200, easing = NmMenuSettle)) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(52.dp).graphicsLayer {
        this.alpha = alpha.value; translationY = with(density) { rise.value.dp.toPx() }
    }.clickable(enabled = state.expanded, onClick = { state.close(action) }).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.08f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(label), fontSize = 16.sp, color = Color.White)
    }
}
