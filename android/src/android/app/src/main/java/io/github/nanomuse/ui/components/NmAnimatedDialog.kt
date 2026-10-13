package io.github.nanomuse.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// nanoMuse: shared, bounded dialog motion; callbacks run after the exit animation completes.
private val NmDialogSpring = Easing { t ->
    if (t >= 1f) 1f else 1f - exp(-8f * t) * (cos(12f * t) + (8f / 12f) * sin(12f * t))
}

@Composable
fun NmAnimatedDialog(onDismiss: () -> Unit,
    content: @Composable (close: (afterExit: () -> Unit) -> Unit) -> Unit) {
    val opacity = remember { Animatable(0f) }
    val scale = remember { Animatable(0.9f) }
    val scrim = remember { Animatable(0f) }
    var afterExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss = rememberUpdatedState(onDismiss)
    fun close(action: () -> Unit) { if (afterExit == null) afterExit = action }
    LaunchedEffect(afterExit) {
        if (afterExit == null) coroutineScope {
            launch { opacity.animateTo(1f, tween(250)) }
            launch { scale.animateTo(1f, tween(250, easing = NmDialogSpring)) }
            launch { scrim.animateTo(0.6f, tween(200)) }
        } else {
            coroutineScope {
                launch { opacity.animateTo(0f, tween(180)) }
                launch { scale.animateTo(0.95f, tween(180)) }
                launch { scrim.animateTo(0f, tween(180)) }
            }
            afterExit?.invoke()
        }
    }
    Dialog(onDismissRequest = { close(dismiss.value) }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) { window?.setDimAmount(0f); onDispose {} }
        val height = LocalConfiguration.current.screenHeightDp.dp * 0.88f
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                close(dismiss.value)
            }.imePadding().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = height).graphicsLayer {
                alpha = opacity.value
                scaleX = scale.value
                scaleY = scale.value
                transformOrigin = TransformOrigin(0.5f, 0f)
            }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(20.dp), color = Color(0xFF1C1C1C),
                contentColor = Color.White) {
                NmMonochrome { content(::close) }
            }
        }
    }
}
