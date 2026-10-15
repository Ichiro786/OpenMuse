package io.github.nanomuse.ui.feed

import io.github.nanomuse.feed.FeedPost
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

// nanoMuse: image extraction preserves text and reaction metadata round-trips without losing posts.
class NmFeedPresentationTest {
    @Test fun `post image moves out of the body without swallowing links`() {
        val parsed = nmFeedBody("A [source](https://example.test/page).\n\n![Preview](https://example.test/image.png)\n\nMore context.")
        assertEquals("https://example.test/image.png", parsed.image)
        assertTrue(parsed.markdown.contains("[source](https://example.test/page)"))
        assertTrue(parsed.markdown.contains("More context."))
        assertFalse(parsed.markdown.contains("![Preview]"))
    }

    @Test fun `code examples and unsupported image schemes are not extracted`() {
        val body = "```markdown\n![Example](https://example.test/image.png)\n```"
        assertEquals(body, nmFeedBody(body).markdown)
        assertNull(nmFeedBody(body).image)
        assertNull(nmFeedBody("![Example](javascript:alert)").image)
        assertEquals("plain text", nmFeedBody("plain text").markdown)
    }

    @Test fun `dislike persists while existing post fields remain intact`() {
        val root = Files.createTempDirectory("nm-feed-test").toFile()
        try {
            val day = File(root, "2026-10-13").apply { mkdir() }
            val file = File(day, "01.md")
            val original = FeedPost(day.name, 1, "A saved note", "note", "", "Keep this body.",
                listOf("https://example.test/source"), 1_792_000_000_000L, false, file, disliked = true)
            file.writeText(original.serialize())
            val restored = FeedPost.parse(file)!!
            assertTrue(restored.disliked)
            assertFalse(restored.liked)
            assertEquals(original.body, restored.body)
            assertEquals(original.source, restored.source)
            assertEquals(original.title, restored.title)
            file.writeText(original.copy(liked = true).serialize())
            assertFalse(FeedPost.parse(file)!!.disliked)
            assertTrue(FeedPost.parse(file)!!.liked)
        } finally { root.deleteRecursively() }
    }
}
