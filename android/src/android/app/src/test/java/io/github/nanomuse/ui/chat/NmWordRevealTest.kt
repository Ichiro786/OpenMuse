package io.github.nanomuse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NmWordRevealTest {
    @Test fun `chunks reveal complete words in order without showing partial tails`() {
        val reveal = NmWordReveal()
        reveal.ingest("One two thre", 0)
        assertEquals(3, reveal.advance(0))
        assertEquals(3, reveal.advance(15))
        assertEquals(7, reveal.advance(16))
        reveal.ingest("One two three ", 32)
        assertEquals(14, reveal.advance(32))
    }

    @Test fun `an unfinished first word waits for its boundary`() {
        val reveal = NmWordReveal()
        reveal.ingest("sea", 0)
        assertEquals(0, reveal.advance(100))
        reveal.ingest("search ", 100)
        assertEquals(7, reveal.advance(100))
    }

    @Test fun `completion and cancellation expose the entire received reply`() {
        val text = "one two three last"
        val reveal = NmWordReveal()
        reveal.ingest(text, 0)
        assertTrue(reveal.hasPending)
        reveal.finish(text)
        assertEquals(text.length, reveal.visibleEnd)
        assertFalse(reveal.hasPending)
        assertEquals(text.length, reveal.advance(1000))
    }

    @Test fun `replacement markdown settles instead of replaying or retaining old offsets`() {
        val reveal = NmWordReveal()
        reveal.ingest("old queued text ", 0)
        reveal.advance(0)
        reveal.ingest("new", 1)
        assertEquals(3, reveal.visibleEnd)
        assertFalse(reveal.hasPending)
    }

    @Test fun `Chinese reveals without waiting for spaces or splitting surrogate pairs`() {
        val reveal = NmWordReveal()
        reveal.ingest("𠀀查", 0)
        assertEquals(2, reveal.advance(0))
        assertEquals(3, reveal.advance(16))
    }

    @Test fun `joined emoji arrive as one word`() {
        val reveal = NmWordReveal()
        reveal.ingest("👩‍💻 code ", 0)
        assertEquals("👩‍💻".length, reveal.advance(0))
        assertEquals("👩‍💻 code ".length, reveal.advance(16))
    }

    @Test fun `large bursts do not leave an unbounded typing backlog`() {
        val text = List(500) { "word" }.joinToString(" ", postfix = " ")
        val reveal = NmWordReveal()
        reveal.ingest(text, 0)
        assertTrue(reveal.visibleEnd > 0)
        assertEquals(text.length, reveal.advance(2048))
        assertFalse(reveal.hasPending)
    }
}
