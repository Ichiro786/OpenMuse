package io.github.nanomuse.ui.chat

import org.junit.Assert.*
import org.junit.Test

// nanoMuse: amplitude filtering stays bounded and independent of display refresh rate.
class NmVoiceUiTest {
    @Test fun `attack follows speech without stepping or overshooting`() {
        var level = 0f
        repeat(30) {
            val next = nmSmoothVoiceLevel(level, 1f, 1f / 60f)
            assertTrue(next >= level && next <= 1f)
            level = next
        }
        assertTrue(level > 0.99f)
        assertTrue(nmSmoothVoiceLevel(0.5f, 1f, 1f / 60f) - 0.5f >
            0.5f - nmSmoothVoiceLevel(0.5f, 0f, 1f / 60f))
    }

    @Test fun `equal elapsed time gives equal envelopes at different frame rates`() {
        var sixty = 0f
        var oneTwenty = 0f
        repeat(60) { sixty = nmSmoothVoiceLevel(sixty, 0.8f, 1f / 60f) }
        repeat(120) { oneTwenty = nmSmoothVoiceLevel(oneTwenty, 0.8f, 1f / 120f) }
        assertEquals(sixty, oneTwenty, 0.0001f)
        assertTrue(nmSmoothVoiceLevel(Float.NaN, Float.POSITIVE_INFINITY, 100f).isFinite())
        assertEquals(0f, nmSmoothVoiceLevel(-4f, -2f, 0.02f), 0f)
    }

    @Test fun `skills preserve dictated text and replace an earlier installed skill`() {
        assertEquals("/Research my spoken question", nmInsertVoiceSkill("my spoken question", "Research", listOf("Research")))
        assertEquals("/Writing keep these words", nmInsertVoiceSkill("/Research keep these words", "Writing", listOf("Research", "Writing")))
        assertEquals("/Research ", nmInsertVoiceSkill("/", "Research", listOf("Research")))
        assertEquals("/Research line one\nline two", nmInsertVoiceSkill("/Research line one\nline two", "Research", listOf("Research")))
    }
}
