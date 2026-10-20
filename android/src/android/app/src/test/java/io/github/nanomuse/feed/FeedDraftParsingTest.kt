package io.github.nanomuse.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedDraftParsingTest {

    private val tagged = """
        Here is today's feed.
        ```nanomuse-feed
        {"emoji": "📰", "title": "One", "type": "brief", "body": "First post.", "source": ["GLOBAL.md"]}
        ```
        ```nanomuse-feed
        {"emoji": "🎯", "title": "Two", "type": "goal", "body": "Second post."}
        ```
    """.trimIndent()

    private val jsonArray = """
        ```json
        [
          {"title": "DeepSeek watch remains quiet", "body": "No new release.", "area": "news", "emoji": "🔭", "image": "", "prompt": "Check"},
          {"title": "Argon versus Sol test", "body": "Gemini 4 Argon is better.", "area": "goals", "emoji": "🧪", "image": "", "prompt": "Create"}
        ]
        ```
    """.trimIndent()

    @Test fun taggedBlocksParseInBothModes() {
        assertEquals(listOf("One", "Two"), FeedFlow.extractDrafts(tagged, loose = false).map { it.title })
        assertEquals(2, FeedFlow.extractDrafts(tagged, loose = true).size)
    }

    @Test fun jsonArrayOnlyCountsInTheFeedConversation() {
        assertTrue(FeedFlow.extractDrafts(jsonArray, loose = false).isEmpty())
        val drafts = FeedFlow.extractDrafts(jsonArray, loose = true)
        assertEquals(listOf("DeepSeek watch remains quiet", "Argon versus Sol test"), drafts.map { it.title })
        assertEquals(listOf("brief", "goal"), drafts.map { it.type })
    }

    @Test fun bareArrayAndPostsWrapperParseLoosely() {
        val bare = """Done. [{"title": "A", "body": "a"}, {"title": "B", "text": "b"}]"""
        assertEquals(listOf("A", "B"), FeedFlow.extractDrafts(bare, loose = true).map { it.title })
        val wrapped = "```json\n{\"posts\": [{\"title\": \"C\", \"body\": \"c\", \"type\": \"idea\"}]}\n```"
        assertEquals("idea", FeedFlow.extractDrafts(wrapped, loose = true).single().type)
    }

    @Test fun postsWithoutTitleOrBodyAreDropped() {
        val text = "```nanomuse-feed\n{\"title\": \"\", \"body\": \"x\"}\n```"
        assertTrue(FeedFlow.extractDrafts(text, loose = true).isEmpty())
    }
}
