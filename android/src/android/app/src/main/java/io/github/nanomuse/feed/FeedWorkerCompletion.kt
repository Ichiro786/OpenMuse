package io.github.nanomuse.feed

import org.json.JSONArray

/** Positive terminal evidence for one headless request, independent of persisted partial rows. */
internal class FeedWorkerCompletion {
    private var generation = 0L
    private var active = false
    private var clean = false

    @Synchronized fun begin(): Long {
        generation++
        active = true
        clean = false
        return generation
    }

    @Synchronized fun interrupted() {
        generation++
        active = false
        clean = false
    }

    @Synchronized fun snapshot(): Long = generation

    @Synchronized fun terminal(run: Long, reason: String?, hasError: Boolean, resumable: Boolean) {
        if (active && generation == run) clean = reason in setOf("stop", "end_turn") && !hasError && !resumable
    }

    @Synchronized fun completed(run: Long): Boolean = active && clean && generation == run

    companion object {
        /** A fresh intermediate tool-call row is never the worker's final data response. */
        fun finalText(partsJson: String): Boolean = runCatching {
            val parts = JSONArray(partsJson)
            val types = (0 until parts.length()).map { parts.optJSONObject(it)?.optString("type") }
            "text" in types && "toolUse" !in types
        }.getOrDefault(false)
    }
}
