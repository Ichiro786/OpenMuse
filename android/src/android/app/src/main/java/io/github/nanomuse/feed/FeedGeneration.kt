package io.github.nanomuse.feed

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One process-wide generation slot. A completion may write only while its original owner holds it. */
internal class FeedGeneration {
    data class Ticket(val id: String, val account: String, val epoch: Long)
    data class Result(val status: String, val count: Int = 0, val error: String? = null, val runId: String? = null) {
        fun json(): String = JSONObject().put("status", status).put("count", count)
            .apply { error?.let { put("error", it) }; runId?.let { put("run_id", it) } }.toString()
    }

    private var epoch = 0L
    private var active: Ticket? = null
    private var worker: String? = null
    private var last: Pair<String, Result>? = null
    private var settled = false

    @Synchronized fun begin(account: String): Ticket? {
        if (active != null) return null
        return Ticket(UUID.randomUUID().toString(), account, epoch).also {
            active = it
            worker = null
            settled = false
            last = account to Result("running", runId = it.id)
        }
    }

    @Synchronized fun bind(ticket: Ticket, session: String): Boolean {
        if (active != ticket || ticket.epoch != epoch || session.isBlank()) return false
        worker = session
        return true
    }

    /** The commit callback runs inside the ownership lock; invalidation cannot race a file write. */
    @Synchronized fun complete(
        ticket: Ticket, account: String, session: String, text: String,
        commit: (List<FeedStore.Draft>) -> Int,
    ): Result {
        if (active != ticket || ticket.epoch != epoch || ticket.account != account || worker != session) {
            return Result("cancelled", error = "owner_changed", runId = ticket.id)
        }
        if (settled) return last!!.second
        val payload = FeedPayload.parse(text)
        val result = if (!payload.valid) Result("error", error = "invalid_feed_output", runId = ticket.id)
        else if (payload.drafts.isEmpty()) Result("empty", runId = ticket.id)
        else {
            try {
                val count = commit(payload.drafts.take(6))
                Result(if (count > 0) "completed" else "empty", count, runId = ticket.id)
            } catch (_: java.io.IOException) {
                Result("error", error = "storage_failed", runId = ticket.id)
            }
        }
        last = account to result
        settled = true
        return result
    }

    @Synchronized fun finish(ticket: Ticket, result: Result? = null) {
        if (active != ticket) return
        result?.let { last = ticket.account to it.copy(runId = ticket.id) }
        active = null
        worker = null
    }

    @Synchronized fun invalidate() {
        epoch++
        active = null
        worker = null
        last = null
    }

    @Synchronized fun status(account: String): Result =
        last?.takeIf { it.first == account }?.second ?: Result("idle")
}

/** Tolerant worker decoder. This parser alone never grants permission to ingest a chat. */
internal object FeedPayload {
    data class Parsed(val drafts: List<FeedStore.Draft>, val valid: Boolean)
    private val fence = Regex("```([A-Za-z0-9_-]*)[ \\t]*\\r?\\n([\\s\\S]*?)```")
    private val aliases = mapOf(
        "news" to "brief", "update" to "brief", "briefing" to "brief", "tech" to "brief", "ai" to "brief",
        "goals" to "goal", "progress" to "goal", "todo" to "reminder", "task" to "reminder", "reminders" to "reminder",
        "ideas" to "idea", "suggestion" to "idea", "memories" to "memory", "notes" to "note",
    )
    val types = setOf("brief", "reminder", "idea", "goal", "memory", "note")

    fun parse(text: String, loose: Boolean = true): Parsed {
        val found = fence.findAll(text).mapNotNull { m ->
            val tag = m.groupValues[1].lowercase()
            if (tag == "nanomuse-feed" || (loose && tag in setOf("", "json", "jsonc"))) decode(m.groupValues[2]) else null
        }.toList()
        val drafts = found.flatMap { it.drafts }
        if (drafts.isNotEmpty() || found.any { it.valid }) return Parsed(drafts, true)
        if (loose) {
            val start = text.indexOfFirst { it == '[' || it == '{' }
            val end = maxOf(text.lastIndexOf(']'), text.lastIndexOf('}'))
            if (start >= 0 && end > start) return decode(text.substring(start, end + 1))
        }
        return Parsed(emptyList(), false)
    }

    private fun decode(raw: String): Parsed {
        val value = runCatching { if (raw.trim().startsWith("[")) JSONArray(raw) else JSONObject(raw) }.getOrNull()
            ?: return Parsed(emptyList(), false)
        val array = when (value) {
            is JSONArray -> value
            is JSONObject -> listOf("posts", "items", "feed").firstNotNullOfOrNull { value.optJSONArray(it) }
                ?: JSONArray().put(value)
            else -> return Parsed(emptyList(), false)
        }
        val drafts = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::draft) }
        return Parsed(drafts, array.length() == 0 || drafts.isNotEmpty())
    }

    private fun draft(o: JSONObject): FeedStore.Draft? {
        val title = o.optString("title").trim().ifEmpty { o.optString("headline").trim() }.ifEmpty { return null }
        val body = listOf("body", "text", "content", "summary").map { o.optString(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val rawType = listOf("type", "area", "category", "kind").map { o.optString(it).trim().lowercase() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val type = rawType.takeIf { it in types } ?: aliases[rawType] ?: "note"
        val source = when (val s = o.opt("source") ?: o.opt("sources")) {
            is JSONArray -> List(s.length()) { s.optString(it).trim() }.filter { it.isNotEmpty() }
            is String -> s.split(";", ",").map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return FeedStore.Draft(title, type, o.optString("emoji").trim().take(4), body, source.take(4))
    }
}
