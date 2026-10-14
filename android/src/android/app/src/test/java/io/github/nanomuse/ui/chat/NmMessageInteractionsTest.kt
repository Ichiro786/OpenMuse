package io.github.nanomuse.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.QueuedPrompt

// nanoMuse: state toggles and durable quote metadata leave message content intact.
class NmMessageInteractionsTest {
    @Test fun `live reply references resolve to persisted rows without changing their snapshot`() {
        val live = NmReplyReference("assistant_live", "assistant", "Partial answer")
        val other = NmReplyReference("user_original", "user", "Question")
        val before = listOf(ChatMessage("assistant_live", "assistant", "Partial answer"))
        assertEquals(listOf(live, other), nmResolveReplyReferences(listOf(live, other), before))
        val persisted = listOf(ChatMessage("assistant_live", "assistant", "Full answer", sourceDbIds = listOf("db_first", "db_final")))
        val resolved = nmResolveReplyReferences(listOf(live, other), persisted)
        assertEquals(NmReplyReference("db_final", "assistant", "Partial answer"), resolved[0])
        assertEquals(other, resolved[1])
        assertEquals("db_final", nmReplyFromPart(JSONObject(nmReplyPartJson(resolved[0])))!!.messageId)
        val selectedEarlier = NmReplyReference("db_first", "assistant", "Earlier text")
        assertEquals(listOf(selectedEarlier), nmResolveReplyReferences(listOf(selectedEarlier), persisted))
    }

    @Test fun `queued caption replacement retains image paths pixels and file inventory`() {
        val queued = listOf(QueuedPrompt("one", "first"), QueuedPrompt("empty", ""), QueuedPrompt("two", "second"))
        val imagePath = AgentContentPart.Text("[attached image: /var/minis/attachments/uploads/photo.png]")
        val image = AgentContentPart.ImageData(byteArrayOf(1, 2, 3), "image/png", linuxPath = "/var/minis/attachments/uploads/photo.png")
        val files = AgentContentPart.Text("<user-attached-files>report.pdf</user-attached-files>")
        val parts = listOf(AgentContentPart.Text("first"), AgentContentPart.Text("second"), imagePath, image, files)
        assertEquals(listOf(AgentContentPart.Text("Reply context and expanded paste"), imagePath, image, files),
            nmQueuedBodyParts("Reply context and expanded paste", queued, parts))
        assertEquals(listOf(AgentContentPart.Text("Reply context"), imagePath, image, files),
            nmQueuedBodyParts("Reply context", listOf(QueuedPrompt("empty", "")), listOf(imagePath, image, files)))
    }

    @Test fun `a saved reference does not move to a later row of a merged bubble`() {
        val original = NmReplyReference("first-row", "assistant", "Earlier answer")
        val merged = ChatMessage("first-row", "assistant", "Earlier answer and continuation",
            sourceDbIds = listOf("first-row", "last-row"))
        assertEquals(listOf(original), nmResolveReplyReferences(listOf(original), listOf(merged)))
    }

    @Test fun `emoji toggles preserve other reactions and remove zero counts`() {
        val state = NmMessageInteractions()
        state.toggleReaction("one", "👍")
        state.toggleReaction("one", "❤️")
        state.toggleReaction("two", "👍")
        assertEquals(mapOf("👍" to 1, "❤️" to 1), state.reactionsById.value.getValue("one").counts)
        assertEquals(setOf("👍", "❤️"), state.reactionsById.value.getValue("one").myReactions)
        state.toggleReaction("one", "👍")
        assertEquals(mapOf("❤️" to 1), state.reactionsById.value.getValue("one").counts)
        state.toggleReaction("one", "❤️")
        assertFalse(state.reactionsById.value.containsKey("one"))
        assertEquals(mapOf("👍" to 1), state.reactionsById.value.getValue("two").counts)
    }

    @Test fun `consuming a sent reply leaves a newer composer reply intact`() {
        val state = NmMessageInteractions()
        val first = NmReplyReference("one", "assistant", "First")
        val second = NmReplyReference("two", "user", "Second")
        state.replyTo(first)
        state.replyTo(second)
        state.consumeReply(listOf(first))
        assertEquals(second, state.replyingTo.value)
        state.consumeReply(listOf(second))
        assertNull(state.replyingTo.value)
        state.replyTo(first)
        state.cancelReply()
        assertNull(state.replyingTo.value)
    }

    @Test fun `reply metadata round trips ID role and quoted text without changing media`() {
        val reply = NmReplyReference("original", "assistant", "A \"quote\"\nNext line")
        val parts = JSONArray("""[{"type":"text","value":"My answer"},{"type":"mediaRef","value":{"relativePath":"photo.png","mimeType":"image/png"}}]""")
        val photo = parts.getJSONObject(1).toString()
        parts.put(JSONObject(nmReplyPartJson(reply)))
        assertEquals(reply, nmReplyFromPart(parts.getJSONObject(2)))
        assertEquals("My answer", parts.getJSONObject(0).getString("value"))
        assertEquals(photo, parts.getJSONObject(1).toString())
        val modelText = nmReplyModelText(listOf(reply), "My answer")
        assertTrue(modelText.contains("original"))
        assertTrue(modelText.contains("assistant"))
        assertTrue(modelText.contains("A \\\"quote\\\"\\nNext line"))
        assertTrue(modelText.endsWith("My answer"))
        assertEquals("My answer", nmReplyModelText(emptyList(), "My answer"))
    }

    @Test fun `malformed reply parts are ignored while valid snapshots are bounded`() {
        for (part in listOf(
            """{"type":"text","value":"ordinary"}""",
            """{"type":"nanomuse-reply","value":{"messageId":"","role":"user","text":"hello"}}""",
            """{"type":"nanomuse-reply","value":{"messageId":"one","role":"system","text":"hello"}}""",
            """{"type":"nanomuse-reply","value":{"messageId":"one","role":"user","text":42}}""",
        )) assertNull(nmReplyFromPart(JSONObject(part)))
        val long = NmReplyReference("one", "user", "x".repeat(10_000))
        val restored = nmReplyFromPart(JSONObject(nmReplyPartJson(long)))!!
        assertTrue(restored.text.length <= 600)
        assertEquals("one", restored.messageId)
    }

    @Test fun `reasoning expansion and session clear stay local to their message`() {
        val state = NmMessageInteractions()
        state.toggleReasoning("one")
        state.toggleReasoning("two")
        state.toggleReasoning("one")
        assertEquals(setOf("two"), state.reasoningExpandedIds.value)
        state.replyTo(NmReplyReference("one", "user", "hello"))
        state.toggleReaction("one", "👍")
        state.clear()
        assertNull(state.replyingTo.value)
        assertTrue(state.reactionsById.value.isEmpty())
        assertTrue(state.reasoningExpandedIds.value.isEmpty())
    }
}
