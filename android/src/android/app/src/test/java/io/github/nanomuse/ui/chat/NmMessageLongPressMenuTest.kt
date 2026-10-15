package io.github.nanomuse.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

// nanoMuse: press-local anchors and the actual visible frame keep menus beside scrolled messages.
class NmMessageLongPressMenuTest {
    private val window = IntRect(0, 0, 360, 640)
    private val menu = IntSize(280, 100)

    @Test fun `middle press places the popup twelve pixels above the finger`() {
        assertEquals(IntOffset(40, 148), nmMessageMenuPosition(
            Offset(180f, 260f), Rect(20f, 240f, 320f, 280f), window, menu, 12,
        ))
    }

    @Test fun `top press uses room below the bubble`() {
        assertEquals(IntOffset(40, 36), nmMessageMenuPosition(
            Offset(180f, 24f), Rect(20f, 8f, 320f, 48f), window, menu, 12,
        ))
    }

    @Test fun `bottom press uses room above the bubble`() {
        assertEquals(IntOffset(40, 504), nmMessageMenuPosition(
            Offset(180f, 616f), Rect(20f, 592f, 320f, 632f), window, menu, 12,
        ))
    }

    @Test fun `tall scrolled bubble anchors to the press instead of its offscreen top`() {
        assertEquals(IntOffset(40, 288), nmMessageMenuPosition(
            Offset(180f, 400f), Rect(20f, -300f, 320f, 900f), window, menu, 12,
        ))
    }

    @Test fun `tall scrolled bubble uses room below a press near the top`() {
        assertEquals(IntOffset(40, 52), nmMessageMenuPosition(
            Offset(180f, 40f), Rect(20f, -300f, 320f, 900f), window, menu, 12,
        ))
    }

    @Test fun `presses near horizontal edges preserve the visible window margin`() {
        val message = Rect(20f, 240f, 320f, 280f)
        assertEquals(IntOffset(12, 148),
            nmMessageMenuPosition(Offset(10f, 260f), message, window, menu, 12))
        assertEquals(IntOffset(68, 148),
            nmMessageMenuPosition(Offset(350f, 260f), message, window, menu, 12))
    }

    @Test fun `expanded grid grows upward beside the same bubble`() {
        assertEquals(IntOffset(40, 368), nmMessageMenuPosition(
            Offset(180f, 600f), Rect(20f, 580f, 320f, 620f),
            window, IntSize(280, 220), 12,
        ))
    }

    @Test fun `visible frame offsets clip the bubble and exclude system bars`() {
        assertEquals(IntOffset(36, 62), nmMessageMenuPosition(
            Offset(40f, 50f), Rect(0f, -40f, 320f, 80f),
            IntRect(24, 32, 344, 608), menu, 12,
        ))
    }

    @Test fun `when neither side fits the popup stays in the visible frame`() {
        assertEquals(IntOffset(40, 12), nmMessageMenuPosition(
            Offset(180f, 400f), Rect(20f, -300f, 320f, 900f),
            window, IntSize(280, 600), 12,
        ))
    }

    @Test fun `small windows clamp without an invalid range`() {
        assertEquals(IntOffset.Zero, nmMessageMenuPosition(
            Offset(40f, 40f), Rect(10f, 10f, 50f, 50f),
            IntRect(0, 0, 80, 80), IntSize(100, 100), 12,
        ))
    }
}
