package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString

internal data class NmStreamingPresentation(
    val streaming: Boolean,
    val revealWords: Boolean,
    val motionScale: Float,
    val ink: Color,
    val phase: State<Float>?,
)

internal val LocalNmStreamingPresentation = compositionLocalOf<NmStreamingPresentation?> { null }

/**
 * Lives inside the assistant bubble, above its static background. The renderer keeps
 * its Markdown parser, links and selection registration; only glyph foregrounds animate.
 */
@Composable
fun NmStreamingText(
    isStreaming: Boolean,
    revealWords: Boolean = isStreaming,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scale = nmChatMotionScale()
    val ink = nmChatInk()
    val phase = if (enabled && isStreaming && scale > 0f) {
        val transition = rememberInfiniteTransition(label = "nmStreamingText")
        transition.animateFloat(
            initialValue = -0.35f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
            label = "nmTextShimmer",
        )
    } else null
    val presentation = remember(enabled, isStreaming, revealWords, scale, ink, phase) {
        if (enabled) NmStreamingPresentation(isStreaming, revealWords, scale, ink, phase) else null
    }
    CompositionLocalProvider(LocalNmStreamingPresentation provides presentation, content = content)
}

/** Adds foreground spans only, leaving annotations, fonts, line breaks and hit testing intact. */
@Composable
internal fun rememberNmStreamingText(
    text: AnnotatedString,
    layoutResult: TextLayoutResult?,
): AnnotatedString? {
    val presentation = LocalNmStreamingPresentation.current ?: return null
    val canReveal = presentation.streaming && presentation.revealWords && presentation.motionScale > 0f
    val interval = (16L * presentation.motionScale).toLong().coerceAtLeast(1L)
    val controller = remember(interval) { NmWordReveal(interval) }
    var visible by remember(controller) { mutableIntStateOf(0) }
    val now = System.nanoTime() / 1_000_000L
    if (canReveal) controller.ingest(text.text, now) else controller.finish(text.text)
    val frontier = if (canReveal) controller.advance(now).coerceIn(0, text.length) else text.length
    // The frame loop runs only while queued words remain. Partial trailing words await data.
    LaunchedEffect(text.text, canReveal, controller) {
        visible = controller.visibleEnd
        while (canReveal && controller.hasPending) {
            withFrameNanos { nanos -> visible = controller.advance(nanos / 1_000_000L) }
        }
    }
    // Observe reveal changes independently of parse/layout updates.
    val end = if (canReveal) maxOf(frontier, visible).coerceAtMost(text.length) else text.length
    val ink = presentation.ink
    // nanoMuse: line edges include bidi/alignment offsets, unlike the container's measured width.
    val extent = remember(layoutResult) {
        layoutResult?.let { result ->
            nmStreamingTextExtent(List(result.lineCount) { line ->
                result.getLineLeft(line)..result.getLineRight(line)
            })
        } ?: (0f..1f)
    }
    val phase = presentation.phase?.value
    val brush = if (presentation.streaming && phase != null) {
        val width = (extent.endInclusive - extent.start).coerceAtLeast(1f)
        val band = width * 0.35f
        val center = extent.start + phase * width
        Brush.linearGradient(
            0f to ink.copy(alpha = 0.55f),
            0.25f to ink.copy(alpha = 0.68f),
            0.5f to ink,
            0.75f to ink.copy(alpha = 0.68f),
            1f to ink.copy(alpha = 0.55f),
            start = Offset(center - band / 2f, 0f),
            end = Offset(center + band / 2f, 0f),
        )
    } else null
    return nmStreamingForeground(text, ink, brush, end)
}

// nanoMuse: blank lines have no glyphs and must not widen an aligned text band.
internal fun nmStreamingTextExtent(
    lines: List<ClosedFloatingPointRange<Float>>,
): ClosedFloatingPointRange<Float> {
    val visible = lines.filter { it.endInclusive > it.start }
    if (visible.isEmpty()) return 0f..1f
    return visible.minOf { it.start }..visible.maxOf { it.endInclusive }
}

/** Pure foreground transformation, shared with annotation/selection regression tests. */
internal fun nmStreamingForeground(
    text: AnnotatedString,
    ink: Color,
    brush: Brush?,
    visibleEnd: Int,
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (length > 0) {
        if (brush != null) addStyle(SpanStyle(brush = brush), 0, length)
        else addStyle(SpanStyle(color = ink), 0, length)
        val end = visibleEnd.coerceIn(0, length)
        if (end < length) addStyle(SpanStyle(brush = SolidColor(ink), alpha = 0f), end, length)
    }
}
