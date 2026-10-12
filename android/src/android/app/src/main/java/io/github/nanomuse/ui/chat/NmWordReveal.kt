package io.github.nanomuse.ui.chat

/** Schedules complete words after parsing, without changing text or Markdown layout. */
internal class NmWordReveal(private val wordMillis: Long = 16L) {
    private data class Word(val end: Int, val at: Long)
    private val pending = ArrayDeque<Word>()
    private var source = ""
    private var scannedEnd = 0
    private var nextAt = 0L
    var visibleEnd = 0
        private set
    val hasPending: Boolean get() = pending.isNotEmpty()

    fun ingest(text: String, now: Long) {
        if (text == source) return
        if (!text.startsWith(source)) {
            finish(text) // Markdown rewrites and replacement text never replay old content.
            return
        }
        source = text
        val ends = completeWordEnds(text, scannedEnd)
        if (ends.isEmpty()) return
        scannedEnd = ends.last()
        nextAt = maxOf(now, nextAt)
        for (end in ends) {
            pending.addLast(Word(end, nextAt))
            nextAt += wordMillis
        }
        // Large catch-up chunks show their prefix immediately; only a bounded tail animates.
        while (pending.size > 128) visibleEnd = pending.removeFirst().end
        if (pending.isNotEmpty() && pending.first().at - now > 2048L) {
            val firstAt = pending.first().at
            val shifted = pending.map { Word(it.end, now + it.at - firstAt) }
            pending.clear()
            pending.addAll(shifted)
            nextAt = pending.last().at + wordMillis
        }
    }

    fun advance(now: Long): Int {
        while (pending.isNotEmpty() && pending.first().at <= now) {
            visibleEnd = pending.removeFirst().end
        }
        return visibleEnd
    }

    fun finish(text: String) {
        source = text
        scannedEnd = text.length
        visibleEnd = text.length
        pending.clear()
        nextAt = 0L
    }

    private fun completeWordEnds(text: String, start: Int): List<Int> {
        val ends = ArrayList<Int>()
        var cursor = start
        while (cursor < text.length) {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor == text.length) {
                // Whitespace after a completed word can become visible with it.
                if (ends.isNotEmpty()) ends[ends.lastIndex] = cursor
                break
            }
            val point = Character.codePointAt(text, cursor)
            if (isCjk(point)) {
                cursor += Character.charCount(point)
                while (cursor < text.length) {
                    val next = Character.codePointAt(text, cursor)
                    val type = Character.getType(next)
                    if (type != Character.NON_SPACING_MARK.toInt() && type != Character.COMBINING_SPACING_MARK.toInt()) break
                    cursor += Character.charCount(next)
                }
                ends.add(cursor)
            } else {
                while (cursor < text.length && !text[cursor].isWhitespace() &&
                    !isCjk(Character.codePointAt(text, cursor))) {
                    cursor += Character.charCount(Character.codePointAt(text, cursor))
                }
                // Keep an incomplete trailing word hidden until a boundary or stream completion.
                if (cursor == text.length) break
                ends.add(cursor)
            }
        }
        return ends
    }

    private fun isCjk(point: Int): Boolean = when (Character.UnicodeScript.of(point)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }
}
