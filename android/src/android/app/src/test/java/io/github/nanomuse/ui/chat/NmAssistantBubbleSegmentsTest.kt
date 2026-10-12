package io.github.nanomuse.ui.chat

import com.openminis.app.ui.chat.FlatChatItem
import org.junit.Assert.*
import org.junit.Test

// nanoMuse: streaming paragraphs join without replacing the renderer's stable row keys.
class NmAssistantBubbleSegmentsTest {
    private fun row(index: Int, raw: String = "Paragraph $index", message: String = "reply") =
        FlatChatItem.AssistantMarkdownBlock(message, "body", raw, index, true, true, raw)

    @Test fun `new paragraphs extend the same surface with only two rounded ends`() {
        val rows = listOf(row(0), row(1), row(2))
        val segments = nmBubbleSegments(rows)
        assertEquals(NmBubbleSegment(true, false), segments[rows[0].key])
        assertEquals(NmBubbleSegment(false, false), segments[rows[1].key])
        assertEquals(NmBubbleSegment(false, true), segments[rows[2].key])
        assertEquals(NmBubbleSegment(true, true), nmBubbleSegments(rows.take(1))[rows[0].key])
        assertEquals(rows.map { it.key }.toSet(), segments.keys)
    }

    @Test fun `code and tables join but inline app cards retain their own frame`() {
        val rows = listOf(row(0), row(1, "```kotlin\nval n = 1\n```"),
            row(2, "| A | B |"), row(3, "```` nanomuse-question\n{}\n````"), row(4))
        val segments = nmBubbleSegments(rows)
        assertEquals(NmBubbleSegment(false, false), segments[rows[1].key])
        assertEquals(NmBubbleSegment(false, true), segments[rows[2].key])
        assertFalse(segments.containsKey(rows[3].key))
        assertEquals(NmBubbleSegment(true, true), segments[rows[4].key])
    }

    @Test fun `different assistant messages never share a bubble`() {
        val rows = listOf(row(0), row(1, message = "another"))
        assertTrue(nmBubbleSegments(rows).values.all { it == NmBubbleSegment(true, true) })
    }
}
