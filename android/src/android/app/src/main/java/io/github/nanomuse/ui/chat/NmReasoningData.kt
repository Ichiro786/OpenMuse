package io.github.nanomuse.ui.chat

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal enum class NmReasoningAction {
    SEARCH, CODE, READ, WRITE, EDIT, IMAGE, MEMORY_READ, MEMORY_WRITE, TOOL,
}

internal fun nmReasoningAction(name: String): NmReasoningAction = when (name.lowercase()) {
    "browser_use", "web_search", "web_fetch", "search", "fetch_url" -> NmReasoningAction.SEARCH
    "shell_execute", "code_execution", "execute_code", "run_code", "python" -> NmReasoningAction.CODE
    "file_read", "read_file" -> NmReasoningAction.READ
    "file_write", "write_file" -> NmReasoningAction.WRITE
    "file_edit", "edit_file" -> NmReasoningAction.EDIT
    "read_image", "image_read" -> NmReasoningAction.IMAGE
    "memory_get", "memory_read", "memory_search" -> NmReasoningAction.MEMORY_READ
    "memory_write", "memory_save" -> NmReasoningAction.MEMORY_WRITE
    else -> NmReasoningAction.TOOL
}

internal enum class NmReasoningSourceKind { WEB, FILE }
internal data class NmReasoningSource(val key: String, val label: String, val kind: NmReasoningSourceKind)

/** Bounded real metadata only. Never display URL credentials, queries or full file paths. */
internal fun nmReasoningSources(
    browserUrl: String?,
    args: String,
    result: String,
): List<NmReasoningSource> {
    val sources = linkedMapOf<String, NmReasoningSource>()
    fun addUrl(value: String) {
        if (sources.size >= 6) return
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return
        val host = uri.host?.removePrefix("www.")?.lowercase() ?: return
        if (host.isBlank()) return
        sources.putIfAbsent("web:$host", NmReasoningSource("web:$host", host, NmReasoningSourceKind.WEB))
    }
    fun addFile(value: String) {
        if (sources.size >= 6 || value.contains("://")) return
        val label = value.replace('\\', '/').substringAfterLast('/').trim()
        if (label.isBlank() || label.length > 120 || label.contains('\n')) return
        sources.putIfAbsent("file:$label", NmReasoningSource("file:$label", label, NmReasoningSourceKind.FILE))
    }
    var visited = 0
    fun walk(value: Any?, depth: Int) {
        if (depth > 4 || ++visited > 80 || sources.size >= 6) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext() && visited < 80 && sources.size < 6) {
                    val key = keys.next()
                    val child = value.opt(key)
                    when (key.lowercase()) {
                        "url", "href", "source_url", "link", "browser_url" ->
                            if (child is String) addUrl(child)
                        "path", "file_path", "filename", "file_name", "image_path" ->
                            if (child is String) addFile(child)
                    }
                    if (child is JSONObject || child is JSONArray) walk(child, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 12)) walk(value.opt(i), depth + 1)
        }
    }
    fun parse(raw: String) {
        if (raw.length > 65_536) return
        runCatching {
            when (raw.trimStart().firstOrNull()) {
                '{' -> walk(JSONObject(raw), 0)
                '[' -> walk(JSONArray(raw), 0)
            }
        }
    }
    browserUrl?.let(::addUrl)
    parse(args)
    parse(result)
    return sources.values.toList()
}
