package io.github.nanomuse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

// nanoMuse: shimmer travels over laid-out text, including indented and RTL lines.
class NmStreamingBoundsTest {
    @Test fun `short reply extent follows glyph lines rather than the bubble width`() {
        assertEquals(0f..24f, nmStreamingTextExtent(listOf(0f..24f)))
    }

    @Test fun `mixed aligned lines share their actual physical extent`() {
        assertEquals(12f..84f, nmStreamingTextExtent(listOf(18f..48f, 12f..84f)))
    }

    @Test fun `empty lines do not pull an indented text band into empty space`() {
        assertEquals(80f..112f, nmStreamingTextExtent(listOf(0f..0f, 80f..112f)))
        assertEquals(0f..1f, nmStreamingTextExtent(emptyList()))
        assertEquals(0f..1f, nmStreamingTextExtent(listOf(0f..0f)))
    }
}
