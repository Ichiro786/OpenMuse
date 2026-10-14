package io.github.nanomuse.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.QueuedPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

// nanoMuse: reply snapshots persist with message parts; reactions and expansion stay in this chat.
data class NmReplyReference(val messageId: String, val role: String, val text: String)

data class NmReactionState(
    val counts: Map<String, Int> = emptyMap(),
    val myReactions: Set<String> = emptySet(),
)

class NmMessageInteractions {
    private val _replyingTo = MutableStateFlow<NmReplyReference?>(null)
    val replyingTo = _replyingTo.asStateFlow()
    private val _reactionsById = MutableStateFlow<Map<String, NmReactionState>>(emptyMap())
    val reactionsById = _reactionsById.asStateFlow()
    private val _reasoningExpandedIds = MutableStateFlow<Set<String>>(emptySet())
    val reasoningExpandedIds = _reasoningExpandedIds.asStateFlow()

    fun replyTo(reply: NmReplyReference) { _replyingTo.value = reply }
    fun cancelReply() { _replyingTo.value = null }

    fun consumeReply(replies: List<NmReplyReference>) {
        val current = _replyingTo.value ?: return
        if (current in replies) _replyingTo.compareAndSet(current, null)
    }

    fun toggleReaction(messageId: String, emoji: String) {
        if (messageId.isBlank() || emoji.isBlank()) return
        _reactionsById.update { all ->
            val old = all[messageId] ?: NmReactionState()
            val removing = emoji in old.myReactions
            val count = (old.counts[emoji] ?: 0) + if (removing) -1 else 1
            val counts = if (count > 0) old.counts + (emoji to count) else old.counts - emoji
            val mine = if (removing) old.myReactions - emoji else old.myReactions + emoji
            if (counts.isEmpty()) all - messageId else all + (messageId to NmReactionState(counts, mine))
        }
    }

    fun toggleReasoning(messageId: String) {
        _reasoningExpandedIds.update { if (messageId in it) it - messageId else it + messageId }
    }

    fun clear() {
        cancelReply()
        _reactionsById.value = emptyMap()
        _reasoningExpandedIds.value = emptySet()
    }
}

private fun NmReplyReference.valueJson(): JSONObject = JSONObject()
    .put("messageId", messageId)
    .put("role", role)
    .put("text", text.take(600))

fun nmReplyPartJson(reply: NmReplyReference): String = JSONObject()
    .put("type", "nanomuse-reply")
    .put("value", reply.valueJson())
    .toString()

fun nmReplyFromPart(part: JSONObject): NmReplyReference? {
    if (part.optString("type") != "nanomuse-reply") return null
    val value = part.optJSONObject("value") ?: return null
    val id = value.opt("messageId") as? String ?: return null
    val role = value.opt("role") as? String ?: return null
    val text = value.opt("text") as? String ?: return null
    if (id.isBlank() || id.length > 512 || role !in setOf("user", "assistant")) return null
    return NmReplyReference(id, role, text.take(600))
}

fun nmReplyModelText(replies: List<NmReplyReference>, text: String): String {
    if (replies.isEmpty()) return text
    val quoted = JSONArray()
    replies.forEach { quoted.put(it.valueJson()) }
    return "The user is replying to these earlier messages (quoted context):\n$quoted\n\n$text"
}

fun nmResolveReplyReferences(
    replies: List<NmReplyReference>,
    messages: List<ChatMessage>,
): List<NmReplyReference> = replies.map { reply ->
    val target = messages.firstOrNull { it.id == reply.messageId }
    val persistedId = target?.let { message ->
        message.sourceDbIds.lastOrNull()?.takeUnless { reply.messageId in message.sourceDbIds }
    }
    if (persistedId == null || persistedId == reply.messageId) reply else reply.copy(messageId = persistedId)
}

/** Only captions precede the image-path and file-inventory text; those parts must survive. */
fun nmQueuedBodyParts(
    body: String,
    queued: List<QueuedPrompt>,
    parts: List<AgentContentPart>,
): List<AgentContentPart> = listOf(AgentContentPart.Text(body)) +
    parts.drop(queued.count { it.text.isNotEmpty() })
