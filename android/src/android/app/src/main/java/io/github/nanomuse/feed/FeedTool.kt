package io.github.nanomuse.feed

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject

/** Called by the native tool loop with its app-owned session id, never a shell-supplied identity. */
object FeedTool {
    const val NAME = "feed_generate"

    fun definition() = AgentToolDefinition(
        name = NAME,
        description = "Generate posts only in the private Feed worker, or read its status. Returns only " +
            "status, count, run_id and an error code. Running means accepted, not published. " +
            "Keep this chat reply to truthful status/count metadata; the user reads posts in Feed.",
        parameters = mapOf(
            "action" to AgentToolParam("string", "generate starts the Feed worker; status checks its latest run.", listOf("generate", "status")),
            "request" to AgentToolParam("string", "Optional context for the requested posts. Never include credentials."),
        ),
        required = listOf("action"),
    )

    suspend fun execute(context: Context, sessionId: String, argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
        val result = when {
            args == null -> FeedGeneration.Result("error", error = "invalid_arguments")
            args.optString("action") == "generate" -> FeedFlow.request(context, sessionId, args.optString("request").take(1500))
            args.optString("action") == "status" -> FeedFlow.request(context, sessionId, statusOnly = true)
            else -> FeedGeneration.Result("error", error = "invalid_arguments")
        }
        return ToolExecutionResult(result.json(), result.status !in setOf("error", "cancelled"))
    }
}
