package io.github.nanomuse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmReasoningDataTest {
    @Test fun `common tool aliases resolve to human action categories`() {
        assertEquals(NmReasoningAction.SEARCH, nmReasoningAction("browser_use"))
        assertEquals(NmReasoningAction.CODE, nmReasoningAction("code_execution"))
        assertEquals(NmReasoningAction.READ, nmReasoningAction("file_read"))
        assertEquals(NmReasoningAction.MEMORY_WRITE, nmReasoningAction("memory_write"))
        assertEquals(NmReasoningAction.TOOL, nmReasoningAction("custom_tool"))
    }

    @Test fun `web pills deduplicate hosts and never show query fragments or user info`() {
        val sources = nmReasoningSources(
            "https://ignored@docs.example/reference?search=term#part",
            """{"url":"https://docs.example/other","sources":[{"url":"https://news.example/story"}]}""",
            "",
        )
        assertEquals(listOf("docs.example", "news.example"), sources.map { it.label })
        assertTrue(sources.all { it.kind == NmReasoningSourceKind.WEB })
    }

    @Test fun `file pills show only real basenames`() {
        val sources = nmReasoningSources(null, """{"path":"/workspace/reports/report.txt"}""", "")
        assertEquals(listOf("report.txt"), sources.map { it.label })
        assertEquals(NmReasoningSourceKind.FILE, sources.single().kind)
    }

    @Test fun `malformed partial and oversized tool data do not invent sources or crash`() {
        assertTrue(nmReasoningSources(null, """{"url":""", "not json").isEmpty())
        assertTrue(nmReasoningSources(null, "", "x".repeat(70_000)).isEmpty())
        assertTrue(nmReasoningSources(null, """{"command":"echo hello","tool_title":"Run a check"}""", "").isEmpty())
    }

    @Test fun `only supported web links become pills and the list remains bounded`() {
        val result = """{"sources":[""" + (1..12).joinToString(",") {
            """{"url":"https://site$it.example/page"}"""
        } + "]}"
        assertEquals(6, nmReasoningSources("javascript:alert(1)", "", result).size)
    }
}
