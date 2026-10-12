package io.github.nanomuse.ui.chat

import com.openminis.app.ui.chat.FlatChatItem

// nanoMuse: join virtual Markdown rows into one rounded answer without losing scroll anchors.
data class NmBubbleSegment(val first: Boolean, val last: Boolean)

internal fun nmBubbleSegments(items: List<FlatChatItem>): Map<String, NmBubbleSegment> {
    fun reply(item: FlatChatItem?): FlatChatItem.AssistantMarkdownBlock? =
        (item as? FlatChatItem.AssistantMarkdownBlock)?.takeUnless {
            val raw = it.rawText.trimStart()
            raw.startsWith("```nanomuse-") || raw.startsWith("~~~nanomuse-")
        }
    return buildMap {
        items.forEachIndexed { index, item ->
            val row = reply(item) ?: return@forEachIndexed
            put(row.key, NmBubbleSegment(
                first = reply(items.getOrNull(index - 1))?.messageId != row.messageId,
                last = reply(items.getOrNull(index + 1))?.messageId != row.messageId,
            ))
        }
    }
}
