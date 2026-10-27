package io.github.nanomuse.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeedSessionPolicyTest {
    @Test fun persistentMarkerSuppressesGenericNotificationWithoutPublishedHiddenIds() {
        assertTrue(FeedSessionPolicy.isWorker("worker", FeedChatOutput.SOURCE, emptySet()))
        assertTrue(FeedSessionPolicy.isWorker("legacy", "scheduled", setOf("legacy")))
        assertFalse(FeedSessionPolicy.isWorker("chat", null, emptySet()))
        assertFalse(FeedSessionPolicy.isWorker("routine", "scheduled", emptySet()))
    }

    @Test fun persistentLookupBlocksWorkersWhileOrdinaryChatsAndDraftsRemainUsable() = runBlocking {
        var reads = 0
        assertFalse(FeedSessionPolicy.canOpenChat("worker", emptySet()) { reads++; FeedChatOutput.SOURCE })
        assertEquals(1, reads)
        assertFalse(FeedSessionPolicy.canOpenChat("legacy", setOf("legacy")) { fail("known worker lookup"); null })
        assertTrue(FeedSessionPolicy.canOpenChat("ordinary", emptySet()) { null })
        assertTrue(FeedSessionPolicy.canOpenChat("__new__draft", emptySet()) { null })
        assertFalse(FeedSessionPolicy.canOpenChat("unknown", emptySet()) { throw java.io.IOException() })
    }
}
