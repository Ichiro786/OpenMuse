package io.github.nanomuse.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.ToolBlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// nanoMuse: answered turns keep one stable summary without losing their real steps.
class NmReasoningPresentationTest {
    private fun tool(id: String, message: String = "reply") = FlatChatItem.AssistantToolUse(
        message, AssistantBlock(id, "tool_use", toolName = "web_search",
            toolStatus = ToolBlockStatus.SUCCESS), emptyList(),
    )
    private fun answer(message: String = "reply", raw: String = "Here is the answer") =
        FlatChatItem.AssistantMarkdownBlock(message, "body", raw, 0, true, true, raw)

    @Test fun `first answer token replaces steps with their original carrier and removes preparing`() {
        val first = tool("search")
        val second = tool("read")
        val typing = FlatChatItem.AssistantTyping("reply")
        val before = nmReasoningPresentation(listOf(first, second, typing))
        assertEquals(listOf(first.key, typing.key), before.items.map { it.key })
        assertEquals(false, before.groups.getValue(first.key).answered)
        assertEquals(listOf(first, second), before.groups.getValue(first.key).steps)

        val text = answer(raw = "H")
        val after = nmReasoningPresentation(listOf(first, second, typing, text))
        assertEquals(listOf(first.key, text.key), after.items.map { it.key })
        assertSame(first, after.items.first())
        assertEquals(true, after.groups.getValue(first.key).answered)
        assertEquals(listOf(first, second), after.groups.getValue(first.key).steps)
    }

    @Test fun `each answered message keeps its own group and real steps`() {
        val first = tool("search", "a")
        val next = tool("read", "a")
        val other = tool("search", "b")
        val result = nmReasoningPresentation(listOf(first, next, answer("a"), other, answer("b")))
        assertEquals(setOf(first.key, other.key), result.groups.keys)
        assertEquals("a", result.groups.getValue(first.key).messageId)
        assertEquals(listOf(first, next), result.groups.getValue(first.key).steps)
        assertEquals(listOf(other), result.groups.getValue(other.key).steps)
    }

    @Test fun `later tool and waiting phase stay in the answered message summary`() {
        val first = tool("search")
        val text = answer()
        val running = FlatChatItem.AssistantToolUse(
            "reply", AssistantBlock("read", "tool_use", toolStatus = ToolBlockStatus.RUNNING),
            emptyList(),
        )
        val result = nmReasoningPresentation(listOf(
            first, text, running, FlatChatItem.AssistantTyping("reply"),
        ))
        assertEquals(listOf(first.key, text.key), result.items.map { it.key })
        assertSame(running, result.groups.getValue(first.key).steps.last())
        assertEquals(true, result.groups.getValue(first.key).answered)
    }

    @Test fun `attachments errors and notices retain their identity and list order`() {
        val first = tool("search")
        val card = answer(raw = "```nanomuse-plan\n{}\n```")
        val second = tool("read")
        val notice = FlatChatItem.AssistantInfo("reply", AssistantBlock("info", "info", "Notice"))
        val error = FlatChatItem.AssistantError("reply", "The request failed")
        val text = answer().let {
            FlatChatItem.AssistantMarkdownBlock("reply", "body", it.rawText, 1, true, true, it.rawText)
        }
        val result = nmReasoningPresentation(listOf(first, card, second, notice, text, error))
        assertEquals(listOf(first.key, card.key, notice.key, text.key, error.key),
            result.items.map { it.key })
        assertSame(card, result.items[1])
        assertSame(notice, result.items[2])
        assertSame(error, result.items.last())
        assertEquals(false, nmReasoningPresentation(listOf(first, card)).groups.getValue(first.key).answered)
    }

    @Test fun `empty text does not collapse and legacy text suppresses preparing`() {
        val first = tool("search")
        val second = tool("read")
        val empty = FlatChatItem.AssistantText("reply", AssistantBlock("text", "text"), true, "")
        assertEquals(false, nmReasoningPresentation(listOf(first, second, empty)).groups.getValue(first.key).answered)
        val whitespace = answer(raw = " \n ")
        assertEquals(false, nmReasoningPresentation(listOf(first, whitespace)).groups.getValue(first.key).answered)
        val legacy = FlatChatItem.AssistantLegacyContent("reply", "Answer", true)
        val result = nmReasoningPresentation(listOf(first, second, legacy,
            FlatChatItem.AssistantTyping("reply")))
        assertEquals(listOf(first.key, legacy.key), result.items.map { it.key })
    }

    @Test fun `preface before tools does not collapse the working group`() {
        val preface = answer(raw = "I will check")
        val first = tool("search")
        val second = tool("read")
        val result = nmReasoningPresentation(listOf(preface, first, second))
        assertEquals(false, result.groups.getValue(first.key).answered)
        assertEquals(listOf(first, second), result.groups.getValue(first.key).steps)
    }

    @Test fun `deduplicated row ids share normalized message state`() {
        val first = tool("search", "reply#1")
        val second = tool("read", "reply#2")
        val result = nmReasoningPresentation(listOf(first, second, answer("reply"),
            FlatChatItem.AssistantTyping("reply#3")))
        assertEquals("reply", result.groups.getValue(first.key).messageId)
        assertEquals(listOf(first, second), result.groups.getValue(first.key).steps)
        assertEquals(2, result.items.size)
    }

    @Test fun `legacy reasoning follows the current thinking setting`() {
        val hidden = FlatChatItem.AssistantThinking(
            "reply", AssistantBlock("think", "thinking", "Thought"), true, false,
            messageThinkingLevel = null,
        )
        assertEquals(emptyMap<String, NmReasoningGroup>(),
            nmReasoningPresentation(listOf(hidden, answer()), thinkingEnabled = false).groups)
        assertEquals(true,
            nmReasoningPresentation(listOf(hidden, answer()), thinkingEnabled = true)
                .groups.getValue(hidden.key).answered)
    }

    @Test fun `disabled reasoning never becomes a summary carrier`() {
        val hidden = FlatChatItem.AssistantThinking(
            "reply", AssistantBlock("think", "thinking", "Thought"), true, false,
            messageThinkingLevel = ThinkingLevel.OFF,
        )
        val step = tool("search")
        val result = nmReasoningPresentation(listOf(hidden, step, answer()))
        assertEquals(setOf(step.key), result.groups.keys)
        assertEquals(listOf(step), result.groups.getValue(step.key).steps)
    }
}
