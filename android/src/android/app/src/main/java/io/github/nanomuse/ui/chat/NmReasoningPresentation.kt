package io.github.nanomuse.ui.chat

import com.openminis.app.ui.chat.FlatChatItem

// nanoMuse: one stable carrier owns the real steps before and after the reply starts.
internal data class NmReasoningGroup(
    val messageId: String,
    val carrierKey: String,
    val steps: List<FlatChatItem>,
    val answered: Boolean,
)

internal data class NmReasoningPresentation(
    val items: List<FlatChatItem>,
    val groups: Map<String, NmReasoningGroup>,
)

private val nmReasoningAttachmentFence = Regex("^(`{3,}|~{3,})\\s*nanomuse-")

/** Keep app cards and errors independent; never replace a real step's identity or metadata. */
internal fun nmReasoningPresentation(
    items: List<FlatChatItem>,
    thinkingEnabled: Boolean = true,
): NmReasoningPresentation {
    val lastAnswerIndex = mutableMapOf<String, Int>()
    val firstStepIndex = mutableMapOf<String, Int>()
    val stepsByMessage = linkedMapOf<String, MutableList<FlatChatItem>>()
    items.forEachIndexed { index, item ->
        val answer = when (item) {
            is FlatChatItem.AssistantText -> item.messageId to item.block.content
            is FlatChatItem.AssistantMarkdownBlock -> item.messageId to item.rawText
            is FlatChatItem.AssistantLegacyContent -> item.messageId to item.content
            else -> null
        }
        if (answer != null && answer.second.isNotBlank() &&
            !nmReasoningAttachmentFence.containsMatchIn(answer.second.trimStart())
        ) {
            lastAnswerIndex[answer.first.substringBefore('#')] = index
        }
        val stepMessageId = when (item) {
            is FlatChatItem.AssistantToolUse ->
                item.messageId.takeIf { item.block.kind == "tool_use" }
            is FlatChatItem.AssistantThinking ->
                item.messageId.takeIf { item.messageThinkingLevel?.isEnabled ?: thinkingEnabled }
            else -> null
        }?.substringBefore('#')
        if (stepMessageId != null) {
            firstStepIndex.putIfAbsent(stepMessageId, index)
            stepsByMessage.getOrPut(stepMessageId) { mutableListOf() }.add(item)
        }
    }
    val groups = stepsByMessage.map { (messageId, steps) ->
        val carrierKey = steps.first().key
        carrierKey to NmReasoningGroup(
            messageId = messageId,
            carrierKey = carrierKey,
            steps = steps,
            // A preface before tools is not their answer. Later steps never undo this edge.
            answered = (lastAnswerIndex[messageId] ?: -1) > firstStepIndex.getValue(messageId),
        )
    }.toMap()
    val hiddenStepKeys = groups.values.flatMap { group -> group.steps.drop(1).map { it.key } }.toSet()
    val rendered = items.filter { item ->
        item.key !in hiddenStepKeys &&
            !(item is FlatChatItem.AssistantTyping &&
                item.messageId.substringBefore('#') in lastAnswerIndex)
    }
    return NmReasoningPresentation(rendered, groups)
}
