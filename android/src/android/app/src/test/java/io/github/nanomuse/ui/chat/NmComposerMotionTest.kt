package io.github.nanomuse.ui.chat

import org.junit.Assert.*
import org.junit.Test

// nanoMuse: voice can occupy the pill's space without entering the system navigation area.
class NmComposerMotionTest {
    @Test fun `expansion releases only the floating navigation space`() {
        assertEquals(84f, nmVoiceBottomInset(84f, 24f, 0f), 0f)
        assertEquals(54f, nmVoiceBottomInset(84f, 24f, 0.5f), 0f)
        assertEquals(24f, nmVoiceBottomInset(84f, 24f, 1f), 0f)
        val insets = (0..10).map { nmVoiceBottomInset(84f, 24f, it / 10f) }
        assertTrue(insets.zipWithNext().all { (before, after) -> after <= before })
        assertTrue(insets.all { it in 24f..84f })
    }

    @Test fun `interrupted animation and unusual insets remain bounded`() {
        assertEquals(84f, nmVoiceBottomInset(84f, 24f, -0.1f), 0f)
        assertEquals(24f, nmVoiceBottomInset(84f, 24f, 1.1f), 0f)
        assertEquals(84f, nmVoiceBottomInset(84f, 24f, Float.NaN), 0f)
        assertEquals(84f, nmVoiceBottomInset(84f, 100f, 1f), 0f)
        assertEquals(0f, nmVoiceBottomInset(84f, -4f, 1f), 0f)
        assertEquals(0f, nmVoiceBottomInset(0f, 0f, 1f), 0f)
    }
}
