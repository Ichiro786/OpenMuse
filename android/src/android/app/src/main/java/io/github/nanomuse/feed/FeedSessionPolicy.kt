package io.github.nanomuse.feed

/** Shared notification/navigation boundary. Persistent source works before hidden flows publish. */
object FeedSessionPolicy {
    fun isWorker(id: String, source: String?, knownWorkers: Set<String>): Boolean =
        source == FeedChatOutput.SOURCE || id in knownWorkers

    suspend fun canOpenChat(id: String, knownWorkers: Set<String>, source: suspend (String) -> String?): Boolean {
        if (id in knownWorkers) return false
        return try {
            !isWorker(id, source(id), knownWorkers)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false // Do not mount a transcript while its worker identity cannot be checked.
        }
    }
}
