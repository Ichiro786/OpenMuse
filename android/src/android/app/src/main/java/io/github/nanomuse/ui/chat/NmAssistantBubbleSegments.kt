package io.github.nanomuse.ui.chat

import com.openminis.app.ui.chat.FlatChatItem

// nanoMuse: join virtual Markdown rows into one rounded answer without losing scroll anchors.
data class NmBubbleSegment(val first: Boolean, val last: Boolean)

private val nmAttachmentFence = Regex("^(`{3,}|~{3,})\\s*nanomuse-")

internal fun nmBubbleSegments(items: List<FlatChatItem>): Map<String, NmBubbleSegment> {
    fun reply(item: FlatChatItem?): String? {
        val (id, raw) = when (item) {
            is FlatChatItem.AssistantMarkdownBlock -> item.messageId to item.rawText
            is FlatChatItem.AssistantText -> item.messageId to item.block.content
            else -> return null
        }
        return id.takeUnless { nmAttachmentFence.containsMatchIn(raw.trimStart()) }
    }
    return buildMap {
        items.forEachIndexed { index, item ->
            val messageId = reply(item) ?: return@forEachIndexed
            put(item.key, NmBubbleSegment(
                first = reply(items.getOrNull(index - 1)) != messageId,
                last = reply(items.getOrNull(index + 1)) != messageId,
            ))
        }
    }
}

// nanoMuse: list spacing belongs after a whole bubble, never between its virtual rows.
internal fun nmBubbleBoundary(segment: NmBubbleSegment?): Boolean = segment?.last != false
