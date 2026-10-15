package io.github.nanomuse.ui.chat

import org.json.JSONObject

// nanoMuse: native agent attachments; optional free text is an explicit option capability.
data class NmOption(val id: String, val label: String, val allowsText: Boolean = false)
data class NmQuestion(val id: String, val title: String, val description: String,
    val options: List<NmOption>)
data class NmPlan(val id: String, val title: String, val description: String,
    val todos: List<String>, val autoApproveSeconds: Int = 10)

internal fun nmQuestions(json: JSONObject): List<NmQuestion>? = runCatching {
    val array = json.getJSONArray("questions")
    require(array.length() in 1..20)
    val questions = List(array.length()) { i ->
        val q = array.getJSONObject(i)
        val options = q.getJSONArray("options")
        require(options.length() in 1..26)
        NmQuestion(q.getString("id"), q.getString("title"), q.optString("description"),
            List(options.length()) { j ->
                val option = options.getJSONObject(j)
                NmOption(option.getString("id"), option.getString("label"), option.optBoolean("allowsText"))
            }).also { question ->
                require(question.id.isNotBlank() && question.title.isNotBlank())
                require(question.options.all { it.id.isNotBlank() && it.label.isNotBlank() })
                require(question.options.map { it.id }.distinct().size == question.options.size)
            }
    }
    require(questions.map { it.id }.distinct().size == questions.size)
    questions
}.getOrNull()

internal fun nmPlan(json: JSONObject): NmPlan? = runCatching {
    val array = json.getJSONArray("todos")
    require(array.length() <= 100)
    NmPlan(json.getString("id"), json.getString("title"), json.optString("description"),
        List(array.length()) { array.getString(it) }.filter { it.isNotBlank() },
        json.optInt("autoApproveSeconds", 10).coerceIn(0, 300)).also {
            require(it.id.isNotBlank() && it.title.isNotBlank())
        }
}.getOrNull()

// Logical timing is independent of the Android animation scale.
internal data class NmPlanTimer(val remainingMs: Long, val cancelled: Boolean = false,
    val submitted: Boolean = false) {
    fun advance(elapsedMs: Long): NmPlanTimer = if (cancelled || submitted) this
        else copy(remainingMs = (remainingMs - elapsedMs.coerceAtLeast(0)).coerceAtLeast(0))
    fun cancel() = copy(cancelled = true)
    fun submit() = copy(cancelled = true, submitted = true)
    val expired: Boolean get() = remainingMs == 0L && !cancelled && !submitted
}

// Used by both Next and Skip so revisiting questions preserves the submitted answer set.
internal fun nmQuestionAnswers(questions: List<NmQuestion>, selections: List<String>,
    otherText: List<String>, skipped: String): Map<String, String> = questions.mapIndexed { i, q ->
    val option = q.options.firstOrNull { it.id == selections.getOrNull(i) }
    q.id to (if (option?.allowsText == true)
        otherText.getOrNull(i).orEmpty().trim().ifBlank { option.label }
        else option?.label ?: skipped)
}.toMap()
