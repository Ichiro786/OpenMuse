package io.github.nanomuse.feed

import org.junit.Assert.*
import org.junit.Test

class FeedWorkerCompletionTest {
    @Test fun intermediateDataIsNotTerminalSuccess() {
        val completion = FeedWorkerCompletion()
        val run = completion.begin()
        assertFalse(completion.completed(run))
        completion.terminal(run, "tool_use", hasError = false, resumable = false)
        assertFalse(completion.completed(run))
        completion.terminal(run, "end_turn", hasError = false, resumable = false)
        assertTrue(completion.completed(run))
    }

    @Test fun cancellationContextAndTurnLimitInvalidateTheRunWithoutRowErrors() {
        for (reason in listOf("cancel", "context_limit", "turn_limit")) {
            val completion = FeedWorkerCompletion()
            val run = completion.begin()
            completion.interrupted()
            // A delayed terminal callback cannot turn a stopped run into a success.
            completion.terminal(run, "stop", hasError = false, resumable = false)
            assertFalse(reason, completion.completed(run))
        }
    }

    @Test fun staleSuccessCannotSettleNewRunAndOnlyCleanTerminalReasonsSucceed() {
        val completion = FeedWorkerCompletion()
        val old = completion.begin()
        completion.terminal(old, "stop", hasError = false, resumable = false)
        val fresh = completion.begin()
        assertFalse(completion.completed(old))
        assertFalse(completion.completed(fresh))
        for (reason in listOf(null, "length", "max_tokens", "tool_use")) {
            completion.terminal(fresh, reason, hasError = false, resumable = false)
            assertFalse(completion.completed(fresh))
        }
        completion.terminal(old, "stop", hasError = false, resumable = false)
        assertFalse(completion.completed(fresh))
        completion.terminal(fresh, "stop", hasError = false, resumable = true)
        assertFalse(completion.completed(fresh))
        completion.terminal(fresh, "stop", hasError = true, resumable = false)
        assertFalse(completion.completed(fresh))
        completion.terminal(fresh, "stop", hasError = false, resumable = false)
        assertTrue(completion.completed(fresh))
    }

    @Test fun freshIntermediateToolRowsCannotSupplyFeedData() {
        assertFalse(FeedWorkerCompletion.finalText("""[{"type":"text","value":"[]"},{"type":"toolUse","value":{"name":"shell_execute"}}]"""))
        assertTrue(FeedWorkerCompletion.finalText("""[{"type":"text","value":"[]"}]"""))
        assertFalse(FeedWorkerCompletion.finalText("not a parts array"))
    }
}
