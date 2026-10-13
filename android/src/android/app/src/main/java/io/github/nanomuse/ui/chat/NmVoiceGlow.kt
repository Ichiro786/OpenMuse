package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
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
import kotlin.math.sqrt

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
    val ink = nmChatInk()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAmplitude = rememberUpdatedState(amplitude)
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
    val thinkingTarget = phase == NmVoicePhase.Thinking ||
        (phase == NmVoicePhase.Done && lastActivePhase == NmVoicePhase.Thinking)
    val beam by animateFloatAsState(if (thinkingTarget) 1f else 0f,
        tween(if (motion > 0f) 350 else 0), label = "voiceBeamShape")
    Canvas(modifier) {
        if (opacity.value <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val sweep = if (motion > 0f) sin(time * (2f * PI.toFloat() / 1.5f)) else 0f
        // nanoMuse: three broad, independently drifting lobes rise from the entire
        // composer's bottom edge. A perceptual envelope makes normal speech visible
        // without amplifying the audio or opening a second microphone.
        val energy = sqrt(level)
        repeat(3) { layer ->
            val drift = if (motion > 0f) sin(time * (0.72f + layer * 0.23f) + layer * 2.1f) else 0f
            val breath = if (motion > 0f) sin(time * (1.05f + layer * 0.17f) + layer * 1.8f) else 0f
            val anchor = 0.24f + layer * 0.26f
            val listenX = anchor + drift * 0.065f
            val beamX = 0.5f + sweep * 0.23f + (layer - 1) * 0.09f
            val centerX = size.width * (listenX + (beamX - listenX) * beam)
            val listenReach = 0.22f + energy * (0.42f + breath * 0.08f)
            val reach = listenReach + (0.25f + layer * 0.025f - listenReach) * beam
            val ellipseWidth = size.width * (0.44f - 0.08f * beam)
            val ellipseHeight = size.height * reach
            val center = Offset(centerX, size.height * 1.015f)
            val listenStrength = 0.3f + energy * 0.36f
            val strength = opacity.value * (listenStrength + (0.34f - listenStrength) * beam)
            scale(scaleX = ellipseWidth / ellipseHeight.coerceAtLeast(1f), scaleY = 1f, pivot = center) {
                drawCircle(brush = Brush.radialGradient(
                    0f to ink.copy(alpha = strength),
                    0.18f to ink.copy(alpha = strength * 0.8f),
                    0.5f to ink.copy(alpha = strength * 0.32f),
                    0.8f to ink.copy(alpha = strength * 0.055f),
                    1f to Color.Transparent, center = center, radius = ellipseHeight.coerceAtLeast(1f)),
                    radius = ellipseHeight, center = center)
            }
        }
    }
}
