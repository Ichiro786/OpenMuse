package io.github.nanomuse.ui.home

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp

/**
 * Paints black outside the pill, within the bottom navigation band.
 * Attached before the pill's clip/Haze modifiers, this uses its actual outline
 * without covering the glass or becoming part of Haze's scrolling source.
 */
internal fun Modifier.amoledNavPillSurround(extent: Dp): Modifier = drawWithCache {
    val outline = CircleShape.createOutline(size, layoutDirection, this)
    val opening = Path().apply {
        when (outline) {
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
    // Draw beyond the pill's local bounds to cover the side gutters, floating
    // gap and system navigation inset. The window clips the outer extent.
    val extentPx = extent.toPx()
    onDrawWithContent {
        clipPath(opening, clipOp = ClipOp.Difference) {
            drawRect(
                color = Color.Black,
                topLeft = Offset(-extentPx, 0f),
                size = Size(size.width + extentPx * 2f, size.height + extentPx),
            )
        }
        drawContent()
    }
}
