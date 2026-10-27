package io.github.nanomuse.feed

/** A presentation projection, never a rewrite of stored history or the person's own messages. */
object FeedChatOutput {
    const val SOURCE = "nanomuse-feed"

    /** Reserved feed fences, including a streamed partial header. Ordinary JSON/code is untouched. */
    fun isReserved(text: String, streaming: Boolean = false): Boolean {
        var outer: Pair<Char, Int>? = null
        val lastLine = text.substringAfterLast('\n')
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart().takeIf { line.length - it.length <= 3 } ?: continue
            val char = trimmed.firstOrNull() ?: continue
            if (char != '`' && char != '~') continue
            val length = trimmed.takeWhile { it == char }.length
            if (length < 3) continue
            val tag = trimmed.drop(length).trim()
            val opened = outer
            if (opened != null) {
                if (char == opened.first && length >= opened.second && tag.isEmpty()) outer = null
                continue
            }
            if (tag.equals(SOURCE, ignoreCase = true) || (tag.startsWith(SOURCE, ignoreCase = true) && tag.getOrNull(SOURCE.length) in listOf(' ', '\t', '{', '['))) return true
            if (streaming && line == lastLine && tag.isNotEmpty() && SOURCE.startsWith(tag, ignoreCase = true)) return true
            outer = char to length
        }
        return false
    }

    fun text(role: String, text: String, streaming: Boolean = false): String =
        if (role == "assistant" && isReserved(text, streaming)) "" else text

    /** Restored text parts may be separate blocks or fragments of one streamed header. */
    fun hideAssistant(role: String, content: String, parts: List<String>, streaming: Boolean = false): Boolean =
        role == "assistant" && (isReserved(content, streaming) ||
            isReserved(parts.joinToString("\n\n"), streaming) || isReserved(parts.joinToString(""), streaming))
}
