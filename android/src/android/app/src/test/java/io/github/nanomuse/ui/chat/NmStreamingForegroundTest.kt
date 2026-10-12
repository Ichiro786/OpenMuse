package io.github.nanomuse.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmStreamingForegroundTest {
    @Test fun `animation preserves the exact text formatting and URL selection annotations`() {
        val original = buildAnnotatedString {
            append("Read the guide")
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 9, 14)
            addStringAnnotation("url", "https://docs.example/guide", 9, 14)
        }
        val animated = nmStreamingForeground(original, Color.White, null, 4)
        assertEquals(original.text, animated.text)
        assertEquals(original.getStringAnnotations("url", 0, original.length),
            animated.getStringAnnotations("url", 0, animated.length))
        assertTrue(animated.spanStyles.contains(original.spanStyles.single()))
    }

    @Test fun `finished response has solid monochrome foreground and no hidden tail`() {
        val original = buildAnnotatedString { append("Finished reply") }
        val result = nmStreamingForeground(original, Color.White, null, original.length)
        assertEquals(1, result.spanStyles.size)
        assertEquals(Color.White, result.spanStyles.single().item.color)
        assertEquals(null, result.spanStyles.single().item.brush)
        assertEquals(original.text, result.text)
    }
}
