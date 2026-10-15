package io.github.nanomuse.ui.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// nanoMuse: payload validation, answer recovery and exactly-once countdown decisions.
class NmAgentCardModelsTest {
    private val payload = """{"questions":[{"id":"q1","title":"Choose a store","options":[{"id":"a","label":"Store A"},{"id":"other","label":"Something else","allowsText":true}]},{"id":"q2","title":"Use a flag?","options":[{"id":"yes","label":"Yes"}]}]}"""

    @Test fun `questions parse and support explicit free text`() {
        val questions = nmQuestions(JSONObject(payload))!!
        assertEquals(2, questions.size)
        assertTrue(questions[0].options.last().allowsText)
        assertEquals(mapOf("q1" to "My store", "q2" to "Skipped"),
            nmQuestionAnswers(questions, listOf("other", ""), listOf("  My store  "), "Skipped"))
    }

    @Test fun `answers preserve previous selections and skip an unanswered question`() {
        val questions = nmQuestions(JSONObject(payload))!!
        assertEquals(mapOf("q1" to "Store A", "q2" to "Yes"),
            nmQuestionAnswers(questions, listOf("a", "yes"), emptyList(), "Skipped"))
        assertEquals("Skipped", nmQuestionAnswers(questions, listOf("a"), emptyList(), "Skipped")["q2"])
    }

    @Test fun `invalid question choices and duplicate identifiers are rejected`() {
        assertNull(nmQuestions(JSONObject("""{"questions":[]}""")))
        assertNull(nmQuestions(JSONObject(payload.replace("\"q2\"", "\"q1\""))))
        assertNull(nmQuestions(JSONObject(payload.replace("\"other\"", "\"a\""))))
        assertNull(nmQuestions(JSONObject("""{"questions":[{"id":"q","title":"Question","options":[]}]}""")))
    }

    @Test fun `plan duration defaults and remains bounded`() {
        val plan = nmPlan(JSONObject("""{"id":"p","title":"Plan","todos":["Read","Write"]}"""))!!
        assertEquals(10, plan.autoApproveSeconds)
        assertEquals(2, plan.todos.size)
        assertEquals(300, nmPlan(JSONObject("""{"id":"p","title":"Plan","todos":[],"autoApproveSeconds":999999}"""))!!.autoApproveSeconds)
        assertNull(nmPlan(JSONObject("""{"id":"p","title":"","todos":[]}""")))
    }

    @Test fun `countdown expires once and manual interaction stops it`() {
        val timer = NmPlanTimer(10_000).advance(4_000)
        assertEquals(6_000L, timer.remainingMs)
        val cancelled = timer.cancel().advance(20_000)
        assertEquals(6_000L, cancelled.remainingMs)
        assertFalse(cancelled.expired)
        val expired = timer.advance(7_000)
        assertTrue(expired.expired)
        assertFalse(expired.submit().advance(50_000).expired)
        assertFalse(timer.submit().expired)
        assertEquals(timer, timer.advance(-10))
    }

    @Test fun `stored decisions are scoped to session message and complete payload`() {
        val a = nmCardStateKey("session-a", "message-a", payload)
        assertEquals(a, nmCardStateKey("session-a", "message-a", payload))
        assertNotEquals(a, nmCardStateKey("session-b", "message-a", payload))
        assertNotEquals(a, nmCardStateKey("session-a", "message-b", payload))
        assertNotEquals(a, nmCardStateKey("session-a", "message-a", payload + " "))
    }
}
