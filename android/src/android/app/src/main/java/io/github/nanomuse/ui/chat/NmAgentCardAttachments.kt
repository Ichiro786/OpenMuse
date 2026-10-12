package io.github.nanomuse.ui.chat

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import org.json.JSONObject
import java.security.MessageDigest

// nanoMuse: card replies use the current session's normal send path and existing risk checks.
internal data class NmAgentCardContext(val sessionId: String, val messageId: String,
    val active: Boolean, val streaming: Boolean, val onSend: (String) -> Unit)
private val LocalNmAgentCards = compositionLocalOf<NmAgentCardContext?> { null }

@Composable
fun NmAgentCardHost(sessionId: String, messageId: String, active: Boolean, streaming: Boolean,
    onSend: (String) -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNmAgentCards provides NmAgentCardContext(
        sessionId, messageId, active, streaming, onSend), content = content)
}

internal fun nmCardStateKey(sessionId: String, messageId: String, code: String): String =
    MessageDigest.getInstance("SHA-256").digest("$sessionId\u0000$messageId\u0000$code".toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Returns true only for the two card protocols; malformed payloads remain visible as text. */
@Composable
internal fun NmAgentCardAttachment(language: String, code: String): Boolean {
    if (language != "nanomuse-question" && language != "nanomuse-plan") return false
    val host = LocalNmAgentCards.current
    if (host?.streaming == true) {
        NmReasoningWorking()
        return true
    }
    val json = remember(code) {
        if (code.length <= 65_536) runCatching { JSONObject(code) }.getOrNull() else null
    } ?: return false
    val questions = remember(json, language) { if (language == "nanomuse-question") nmQuestions(json) else null }
    val plan = remember(json, language) { if (language == "nanomuse-plan") nmPlan(json) else null }
    if (questions == null && plan == null) return false
    val context = LocalContext.current
    val key = remember(host?.sessionId, host?.messageId, code) {
        nmCardStateKey(host?.sessionId.orEmpty(), host?.messageId.orEmpty(), code)
    }
    val preferences = remember(context) { context.getSharedPreferences("nm_agent_card_decisions", Context.MODE_PRIVATE) }
    var handled by remember(key) { mutableStateOf(preferences.getBoolean(key, false)) }
    val currentHost = rememberUpdatedState(host)
    fun sendOnce(text: String) {
        val current = currentHost.value ?: return
        if (!current.active || current.streaming || handled || preferences.getBoolean(key, false)) return
        handled = true
        preferences.edit().putBoolean(key, true).apply()
        current.onSend(text)
    }
    val active = host?.active == true && !handled
    if (questions != null) {
        val heading = stringResource(R.string.nm_card_answers)
        NmQuestionCard(questions, onSelect = { _, _ -> }, onSkip = {}, onNext = {},
            onPrevious = {}, enabled = active, stateKey = key,
            onComplete = { answers ->
                sendOnce(heading + "\n" + questions.joinToString("\n") { question ->
                    "${question.title}: ${answers[question.id].orEmpty()}"
                })
            })
    } else if (plan != null) {
        val approval = stringResource(R.string.nm_card_plan_reply, plan.title)
        NmPlanCard(plan, onApprove = { sendOnce(approval) }, onViewPlan = {},
            onAutoApprove = { sendOnce(approval) }, enabled = active, stateKey = key)
    }
    if (handled) Text(stringResource(R.string.nm_card_submitted), color = NmCardMuted)
    return true
}

// Agent instructions use the same fenced attachment mechanism as the existing goal/feed cards.
object NmAgentCardProtocol {
    fun promptParagraph(): String = """
        Native inline chat cards are available when you need a decision before continuing.
        For a clarification with choices, emit one fenced `nanomuse-question` JSON object:
        {"questions":[{"id":"q1","title":"The question","description":"Why it matters","options":[{"id":"a","label":"First choice"},{"id":"other","label":"Something else…","allowsText":true}]}]}
        Ask only necessary questions; use the conversation's language for all visible text.
        For a multi-step task whose plan you want confirmed, emit one fenced `nanomuse-plan` object:
        {"id":"unique-plan-id","title":"Plan title","description":"What it will do","todos":["First step","Second step"],"autoApproveSeconds":10}
        Plan cards can approve after their displayed countdown; use them for ordinary task planning.
        Emit at most one pending card per reply, at the end, then END the turn and wait for a user reply.
        A question answer or plan approval arrives as a normal user message. Continue only after it arrives.
        These cards do not authorize sending, deleting, payments, installs or other guarded actions:
        those still require their normal Sentinel/risk approval at execution time.
        Do not emit these cards for routine conversation or tasks that need no clarification or plan.
    """.trimIndent()
}
