package io.github.nanomuse.feed

import org.junit.Assert.*
import org.junit.Test

class FeedChatOutputTest {
    @Test fun hidesLegacyAssistantTurnWithPrefaceAndSeveralPostsWithoutChangingUserText() {
        val text = "Here's today's feed\n```nanomuse-feed\n{\"body\":\"private post\"}\n```\n```nanomuse-feed\n[]\n```\nDone"
        assertEquals("", FeedChatOutput.text("assistant", text))
        assertEquals(text, FeedChatOutput.text("user", text))
        assertEquals(text, FeedChatOutput.text("system", text))
    }

    @Test fun hidesEveryStreamedReservedHeaderAndUnclosedPayload() {
        val header = "```nanomuse-feed"
        for (n in 4..header.length) assertEquals("prefix $n", "", FeedChatOutput.text("assistant", "Preface\n" + header.take(n), true))
        assertEquals("", FeedChatOutput.text("assistant", "$header\n{\"body\":", true))
        assertEquals("", FeedChatOutput.text("assistant", "~~~NANOMUSE-FEED\r\n[]\r\n~~~"))
    }

    @Test fun ordinaryJsonCodeQuotesAndSelectedPostDiscussionSurvive() {
        for (text in listOf(
            "```json\n[{\"title\":\"Example\",\"body\":\"Example\"}]\n```",
            "[{\"title\":\"Example\",\"body\":\"Example\"}]",
            "This selected post suggests a useful next step. Here is why.",
            "Use the `nanomuse-feed` data format.",
            "````markdown\n```nanomuse-feed\n[]\n```\n````",
            "> ```nanomuse-feed\n> []\n> ```",
            "```kotlin\nval name = \"nanomuse-feed\"\n```",
        )) assertEquals(text, FeedChatOutput.text("assistant", text))
    }

    @Test fun restoredTextPartsCannotLeakWhenPrefaceHasNoTrailingNewline() {
        val parts = listOf("Preface", "```nanomuse-feed\n[]\n```")
        assertTrue(FeedChatOutput.hideAssistant("assistant", "", parts))
        assertFalse(FeedChatOutput.hideAssistant("user", "", parts))
        assertTrue(FeedChatOutput.hideAssistant("assistant", "", listOf("```nano", "muse-feed\n[]\n```")))
        assertFalse(FeedChatOutput.hideAssistant("assistant", "", listOf("```json\n[]\n```")))
    }
}
