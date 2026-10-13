package io.github.nanomuse.ui.chat

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.*
import org.junit.Test

// nanoMuse: anchored menus remain inside an IME-adjusted viewport and dispatch actions only once.
class NmMenuTest {
    @Test fun `popup stays above plus with the keyboard already open`() {
        val anchor = IntRect(16, 300, 56, 340)
        val menu = IntSize(248, 224)
        val point = nmPlusMenuPosition(anchor, IntSize(400, 360), menu, 8)
        assertEquals(IntOffset(16, 68), point)
        assertTrue(point.y + menu.height < anchor.top)
        val narrow = nmPlusMenuPosition(IntRect(185, 300, 225, 340), IntSize(220, 360), IntSize(200, 180), 8)
        assertEquals(20, narrow.x)
        assertTrue(narrow.x + 200 <= 220)
        assertTrue(nmPlusMenuPosition(IntRect(0, 4, 40, 44), IntSize(220, 180), IntSize(200, 160), 8).y >= 0)
    }

    @Test fun `selection waits for close and reopening cancels stale action`() {
        val state = NmPlusMenuState()
        state.toggle()
        assertTrue(state.expanded && state.present)
        state.close(NmPlusAction.Photos)
        assertFalse(state.expanded)
        assertTrue(state.present)
        assertEquals(NmPlusAction.Photos, state.finishClose())
        assertFalse(state.present)
        assertNull(state.finishClose())
        state.toggle()
        state.close(NmPlusAction.Camera)
        state.toggle()
        assertNull(state.finishClose())
        assertTrue(state.expanded && state.present)
        state.close()
        assertNull(state.finishClose())
    }

    @Test fun `blur budget adapts to refresh rate and tolerates startup frames`() {
        for (hz in listOf(60, 90, 120)) {
            val interval = 1_000_000_000L / hz
            val smooth = NmMenuFrameBudget(interval)
            repeat(60) { assertFalse(smooth.record(interval)) }
            val slow = NmMenuFrameBudget(interval)
            repeat(4) { assertFalse(slow.record(interval * 3)) }
            var fallsBack = false
            repeat(16) { fallsBack = slow.record(interval * 2) || fallsBack }
            assertTrue(fallsBack)
            assertFalse(NmMenuFrameBudget(interval).record(-1L))
        }
    }
}
