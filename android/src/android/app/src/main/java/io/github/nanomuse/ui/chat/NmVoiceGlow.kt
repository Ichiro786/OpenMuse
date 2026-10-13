package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// nanoMuse: microphone-driven, three-layer monochrome glow; no additional audio capture.
enum class NmVoicePhase { Idle, Listening, Thinking, Done }

internal fun nmSmoothVoiceLevel(current: Float, target: Float, elapsedSeconds: Float): Float {
    val safeTarget = if (target.isFinite()) target.coerceIn(0f, 1f) else 0f
    val safeCurrent = if (current.isFinite()) current.coerceIn(0f, 1f) else 0f
    val tau = if (safeTarget > safeCurrent) 0.075f else 0.18f
    val amount = 1f - exp(-elapsedSeconds.coerceIn(0f, 0.1f) / tau)
    return (safeCurrent + (safeTarget - safeCurrent) * amount).coerceIn(0f, 1f)
}

@Composable
fun NmVoiceGlow(phase: NmVoicePhase, amplitude: Float, modifier: Modifier = Modifier) {
    val motion = nmChatMotionScale()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAmplitude = rememberUpdatedState(amplitude)
    val currentPhase = rememberUpdatedState(phase)
    var lastActivePhase by remember { mutableStateOf(NmVoicePhase.Idle) }
    SideEffect {
        if (phase == NmVoicePhase.Listening || phase == NmVoicePhase.Thinking) lastActivePhase = phase
    }
    var level by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        opacity.animateTo(if (phase == NmVoicePhase.Listening || phase == NmVoicePhase.Thinking) 1f else 0f,
            tween(if (phase == NmVoicePhase.Done) 300 else 180))
    }
    LaunchedEffect(lifecycle, phase, motion) {
        if (phase == NmVoicePhase.Idle || phase == NmVoicePhase.Done) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (true) {
                withFrameNanos { nanos ->
                    val dt = if (previous == 0L) 1f / 60f else ((nanos - previous) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    previous = nanos
                    level = nmSmoothVoiceLevel(level, currentAmplitude.value, dt)
                    if (motion > 0f) time += dt / motion.coerceAtLeast(0.1f)
                }
            }
        }
    }
    Canvas(modifier) {
        if (opacity.value <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val thinking = currentPhase.value == NmVoicePhase.Thinking ||
            (currentPhase.value == NmVoicePhase.Done && lastActivePhase == NmVoicePhase.Thinking)
        val sweep = if (thinking) sin(time * (2f * PI.toFloat() / 1.5f)) else 0f
        repeat(3) { layer ->
            val drift = if (motion > 0f) sin(time * (0.65f + layer * 0.21f) + layer * 2.1f) else 0f
            val centerX = size.width * (0.5f + (if (thinking) sweep * 0.22f else drift * 0.14f))
            val reach = if (thinking) 0.22f + layer * 0.035f else 0.16f + level * 0.62f + layer * 0.035f
            val ellipseWidth = size.width * (0.44f + layer * 0.09f)
            val ellipseHeight = size.height * reach
            val center = Offset(centerX, size.height * 1.01f)
            val strength = opacity.value * (if (thinking) 0.22f else 0.18f + level * 0.32f) * (1f - layer * 0.17f)
            scale(scaleX = ellipseWidth / ellipseHeight.coerceAtLeast(1f), scaleY = 1f, pivot = center) {
                drawCircle(brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = strength),
                    0.35f to Color(0xFFDDDDDD).copy(alpha = strength * 0.7f),
                    0.75f to Color(0xFFAAAAAA).copy(alpha = strength * 0.18f),
                    1f to Color.Transparent, center = center, radius = ellipseHeight.coerceAtLeast(1f)),
                    radius = ellipseHeight, center = center)
            }
        }
    }
}
