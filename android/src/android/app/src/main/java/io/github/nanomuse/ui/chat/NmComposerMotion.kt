package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics

// nanoMuse: one composer surface and interruptible motion for text, voice and navigation.
internal val NmComposerSurface = Color(0xFF141414)

// nanoMuse: the default spatial stiffness, with critical damping to prevent the
// covered navigation pill from reappearing on a rebound. This is our motion choice.
internal fun <T> nmComposerSpring(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 380f)

@Composable
internal fun rememberNmVoiceExpansion(focused: Boolean): State<Float> =
    animateFloatAsState(if (focused) 1f else 0f, nmComposerSpring(), label = "nmVoiceExpansion")

internal fun nmVoiceBottomInset(barInset: Float, systemInset: Float, expansion: Float): Float {
    val bottom = barInset.coerceAtLeast(0f)
    val system = systemInset.coerceIn(0f, bottom)
    val fraction = if (expansion.isFinite()) expansion.coerceIn(0f, 1f) else 0f
    return bottom + (system - bottom) * fraction
}

internal fun Modifier.nmVoiceNavigationRetreat(expansion: Float): Modifier {
    val fraction = expansion.coerceIn(0f, 1f)
    return graphicsLayer {
        translationY = size.height * fraction
        alpha = 1f - fraction
    }.then(if (fraction > 0f) Modifier.clearAndSetSemantics { } else Modifier)
}

// nanoMuse: moving the existing editor preserves its cursor, selection and IME state.
@Composable
internal fun rememberNmComposerEditor(editor: @Composable (Modifier) -> Unit): @Composable (Modifier) -> Unit {
    val current = rememberUpdatedState(editor)
    return remember { movableContentOf<Modifier> { modifier -> current.value(modifier) } }
}
