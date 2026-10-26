package io.github.nanomuse.feed

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FeedGenerationTest {
    private val post = """[{"title":"A","body":"Grounded note","type":"note"}]"""

    @Test fun onlyBoundCurrentAccountWorkerMayCommit() {
        val runs = FeedGeneration()
        val ticket = runs.begin("one")!!
        var writes = 0
        assertEquals("cancelled", runs.complete(ticket, "one", "chat", post) { ++writes }.status)
        assertTrue(runs.bind(ticket, "worker"))
        assertEquals("cancelled", runs.complete(ticket, "one", "chat", post) { ++writes }.status)
        assertEquals("cancelled", runs.complete(ticket, "two", "worker", post) { ++writes }.status)
        assertEquals(0, writes)
        assertEquals(1, runs.complete(ticket, "one", "worker", post) { ++writes }.count)
        assertEquals(1, runs.complete(ticket, "one", "worker", post) { ++writes }.count)
        assertEquals(1, writes)
    }

    @Test fun invalidationRejectsOldCompletionAndOldFinishCannotClearNewRun() {
        val runs = FeedGeneration()
        val old = runs.begin("one")!!
        runs.bind(old, "worker")
        runs.invalidate()
        val fresh = runs.begin("two")!!
        runs.bind(fresh, "other")
        assertEquals("cancelled", runs.complete(old, "one", "worker", post) { fail("stale write"); 0 }.status)
        runs.finish(old, FeedGeneration.Result("error"))
        assertNull(runs.begin("two"))
        assertEquals("idle", runs.status("one").status)
        assertEquals(fresh.id, runs.status("two").runId)
    }

    @Test fun simultaneousEntryHasExactlyOneOwner() {
        val runs = FeedGeneration()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val attempts = (1..8).map { pool.submit<FeedGeneration.Ticket?> { start.await(); runs.begin("one") } }
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) != null })
        } finally { pool.shutdownNow() }
    }

    @Test fun accountInvalidationWaitsForAlreadyOwnedCommit() {
        val runs = FeedGeneration()
        val ticket = runs.begin("one")!!
        runs.bind(ticket, "worker")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val commit = pool.submit { runs.complete(ticket, "one", "worker", post) {
                entered.countDown(); release.await(); 1
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val invalidation = pool.submit { runs.invalidate() }
            assertFalse(invalidation.isDone)
            release.countDown()
            commit.get(5, TimeUnit.SECONDS)
            invalidation.get(5, TimeUnit.SECONDS)
            assertEquals("idle", runs.status("one").status)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun emptyMalformedFailedStorageAndDedupHaveDistinctOutcomes() {
        fun result(text: String, commit: (List<FeedStore.Draft>) -> Int): FeedGeneration.Result {
            val runs = FeedGeneration()
            val ticket = runs.begin("one")!!
            runs.bind(ticket, "worker")
            return runs.complete(ticket, "one", "worker", text, commit)
        }
        assertEquals("empty", result("[]") { fail("empty write"); 0 }.status)
        assertEquals("error", result("no updates") { fail("malformed write"); 0 }.status)
        assertEquals("error", result("[{broken") { fail("malformed write"); 0 }.status)
        assertEquals("empty", result(post) { 0 }.status)
        assertEquals("storage_failed", result(post) { throw java.io.IOException("private path") }.error)
    }

    @Test fun failureAfterSuccessReportsNoOldCountOrPayload() {
        val runs = FeedGeneration()
        val first = runs.begin("one")!!
        runs.bind(first, "worker")
        runs.complete(first, "one", "worker", post) { 3 }
        runs.finish(first)
        val next = runs.begin("one")!!
        assertEquals(0, runs.status("one").count)
        runs.finish(next, FeedGeneration.Result("error", error = "timeout"))
        val json = org.json.JSONObject(runs.status("one").json())
        assertEquals(setOf("status", "count", "run_id", "error"), json.keySet())
        assertEquals(0, json.getInt("count"))
        assertEquals(next.id, json.getString("run_id"))
        assertFalse(json.toString().contains("Grounded note"))
    }

    @Test fun workerBatchIsBoundedEvenIfModelIgnoresPromptLimit() {
        val runs = FeedGeneration()
        val ticket = runs.begin("one")!!
        runs.bind(ticket, "worker")
        val posts = org.json.JSONArray((1..10).map { org.json.JSONObject().put("title", "Title $it").put("body", "Body $it") })
        assertEquals(6, runs.complete(ticket, "one", "worker", posts.toString()) { it.size }.count)
    }
}
