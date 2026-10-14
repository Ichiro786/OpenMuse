package io.github.nanomuse.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

// nanoMuse: menu anchors use window coordinates and stay inside the visible window.
class NmMessageLongPressMenuTest {
    @Test fun `popup sits above the message and centers on the press`() {
        assertEquals(IntOffset(40, 132), nmMessageMenuPosition(
            Offset(180f, 260f), Rect(20f, 240f, 320f, 280f),
            IntSize(360, 640), IntSize(280, 100), 8,
        ))
    }

    @Test fun `presses near window edges keep an eight pixel margin`() {
        val window = IntSize(360, 640)
        val menu = IntSize(280, 100)
        val message = Rect(20f, 240f, 320f, 280f)
        assertEquals(IntOffset(8, 132),
            nmMessageMenuPosition(Offset(10f, 260f), message, window, menu, 8))
        assertEquals(IntOffset(72, 132),
            nmMessageMenuPosition(Offset(350f, 260f), message, window, menu, 8))
        assertEquals(IntOffset(40, 8), nmMessageMenuPosition(
            Offset(180f, 40f), Rect(20f, 20f, 320f, 60f), window, menu, 8,
        ))
    }

    @Test fun `expanded grid grows upward while the message anchor stays fixed`() {
        assertEquals(IntOffset(40, 352), nmMessageMenuPosition(
            Offset(180f, 600f), Rect(20f, 580f, 320f, 620f),
            IntSize(360, 640), IntSize(280, 220), 8,
        ))
    }

    @Test fun `small windows clamp without an invalid range`() {
        assertEquals(IntOffset.Zero, nmMessageMenuPosition(
            Offset(40f, 40f), Rect(10f, 10f, 50f, 50f),
            IntSize(80, 80), IntSize(100, 100), 8,
        ))
    }
}
