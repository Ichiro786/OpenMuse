package io.github.nanomuse.feed

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room queries, including explicit ids. Raw history remains available for local backup. */
@RunWith(AndroidJUnit4::class)
class FeedSessionVisibilityTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val normal = "feed-test-normal"
    private val worker = "feed-test-worker"
    private val text = """[{"type":"text","value":"feed-test-needle"}]"""

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = ChatRepository(db.chatDao())
        for (id in listOf(normal, worker)) {
            db.chatDao().insertSession(ChatSessionEntity(id, "feed-test-needle", "model", 1L, 1L,
                source = if (id == worker) FeedChatOutput.SOURCE else null))
            db.chatDao().insertMessage(MessageEntity("row-$id", id, "assistant", text, 1L, sortOrder = 1))
        }
    }

    @After fun close() { db.close() }

    @Test fun listsAndSearchNeverDiscoverWorker() = runBlocking {
        assertEquals(listOf(normal), repo.observeSessions().first().map { it.id })
        assertEquals(listOf(normal), repo.searchSessions("feed-test-needle").map { it.id })
        assertEquals(listOf(normal), repo.querySessionsMeta(null, listOf("feed-test-needle"), 10, null, null).map { it.id })
        assertEquals(listOf(normal), repo.searchMessages(null, listOf("feed-test-needle"), 10, null, null).map { it.sessionId })
    }

    @Test fun explicitIdsCannotBypassNativeToolBoundary() = runBlocking {
        assertTrue(repo.querySessionsMeta(listOf(worker), null, 10, null, null).isEmpty())
        assertTrue(repo.searchMessages(listOf(worker), listOf("feed-test-needle"), 10, null, null).isEmpty())
        assertTrue(repo.loadMessagePage(worker, 0, 10).isEmpty())
        assertEquals(0, repo.messageCountInRange(worker, null, null))
        assertEquals(1, repo.loadMessagePage(normal, 0, 10).size)
    }

    @Test fun hiddenWorkerRowsAreRetainedForBackup() = runBlocking {
        assertEquals(text, repo.loadMessagePageRaw(worker, 0, 10).single().partsJson)
        assertEquals(text, db.chatDao().loadMessages(worker).single().partsJson)
        assertNotNull(repo.getSession(worker))
    }
}
