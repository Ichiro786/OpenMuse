package io.github.nanomuse.ui.chat

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// nanoMuse: a physical right drag replies only after crossing the release threshold.
class NmSwipeToReplyTest {
    private val target = NmSwipeReplyTarget("reply#1", "row", Rect(16f, 80f, 300f, 140f))

    @Test fun `exact threshold does not reply and the next pixel does`() {
        val state = NmSwipeToReplyState(64f)
        state.begin(target)
        state.drag(64f)
        assertNull(state.replyOnRelease())
        state.drag(1f)
        assertEquals("reply", state.replyOnRelease())
    }

    @Test fun `left drag cannot activate reply or move a bubble left`() {
        val state = NmSwipeToReplyState(64f)
        state.begin(target)
        state.drag(-80f)
        assertNull(state.replyOnRelease())
        assertEquals(0f, state.translationPx, 0f)
    }

    @Test fun `leftward travel is retained before a rightward correction`() {
        val state = NmSwipeToReplyState(64f)
        state.begin(target)
        state.drag(-100f)
        state.drag(65f)
        assertNull(state.replyOnRelease())
        assertEquals(0f, state.translationPx, 0f)
    }

    @Test fun `returning below threshold before release cancels reply`() {
        val state = NmSwipeToReplyState(64f)
        state.begin(target)
        state.drag(90f)
        state.drag(-40f)
        assertNull(state.replyOnRelease())
    }

    @Test fun `all rows share one normalized message offset with bounded movement`() {
        val state = NmSwipeToReplyState(64f)
        state.begin(target)
        state.drag(160f)
        assertEquals("reply", state.target?.messageId)
        assertEquals("row", state.target?.rowKey)
        assertEquals(1f, state.progress, 0f)
        assertTrue(state.translationPx > 0f && state.translationPx < 64f)
        state.begin(target.copy(messageId = "other", rowKey = "next"))
        assertEquals("other", state.target?.messageId)
        assertEquals(0f, state.translationPx, 0f)
        assertNull(state.replyOnRelease())
    }
}
