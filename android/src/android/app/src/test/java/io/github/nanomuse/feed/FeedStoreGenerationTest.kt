package io.github.nanomuse.feed

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FeedStoreGenerationTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = 1_800_000_000_000L

    @Before fun init() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(folder.root)
        FeedStore.init(context)
    }

    private fun draft(n: Int) = FeedStore.Draft("Post $n", "note", "", "Body $n", emptyList())

    @Test fun realFilesKeepCapDedupNewestBatchOrderAndReactions() {
        val first = FeedStore.append(listOf(draft(1), draft(2)), now)
        FeedStore.setLiked(first[0], true)
        FeedStore.setDisliked(FeedStore.posts.value.first { it.id == first[0].id }, true)
        val second = FeedStore.append(listOf(draft(1), draft(3), draft(4)), now + 1000)
        assertEquals(listOf("Post 3", "Post 4"), second.map { it.title })
        assertEquals(listOf("Post 3", "Post 4", "Post 1", "Post 2"), FeedStore.posts.value.map { it.title })
        assertTrue(FeedPost.parse(first[0].file)!!.disliked)
        assertFalse(FeedPost.parse(first[0].file)!!.liked)
        FeedStore.append((5..30).map(::draft), now + 2000)
        assertEquals(12, FeedStore.posts.value.size)
        assertEquals(0, FeedStore.remainingCapacity(now))
        assertTrue(FeedStore.append(listOf(draft(31)), now).isEmpty())
    }

    @Test fun duplicateTitlesBodiesAndLongTitlesDoNotUseSlots() {
        FeedStore.append(listOf(draft(1)), now)
        assertTrue(FeedStore.append(listOf(draft(1).copy(title = " post 1 "), draft(8).copy(body = "Body 1")), now).isEmpty())
        val title = "x".repeat(100)
        FeedStore.append(listOf(draft(9).copy(title = title)), now)
        assertTrue(FeedStore.append(listOf(draft(10).copy(title = title)), now).isEmpty())
        assertEquals(10, FeedStore.remainingCapacity(now))
    }

    @Test fun concurrentAppendsCannotReuseNumbersOrOverrunCap() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            (1..4).map { batch -> pool.submit { FeedStore.append((1..8).map { draft(batch * 100 + it) }, now) } }
                .forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(12, FeedStore.posts.value.size)
            assertEquals(12, FeedStore.posts.value.map { it.id }.toSet().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun unwritableStorageIsAnErrorRatherThanEmptySuccess() {
        val root = File(folder.root, "minis-global/nanomuse/feed")
        root.deleteRecursively()
        root.writeText("blocks directory creation")
        val runs = FeedGeneration()
        val ticket = runs.begin("account")!!
        runs.bind(ticket, "worker")
        val result = runs.complete(ticket, "account", "worker", """[{"title":"A","body":"B"}]""") {
            FeedStore.append(it, now).size
        }
        assertEquals("error", result.status)
        assertEquals("storage_failed", result.error)
        assertEquals(0, result.count)
    }
}
