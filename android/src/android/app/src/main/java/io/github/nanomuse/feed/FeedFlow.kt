package io.github.nanomuse.feed

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.AgentForegroundService
import com.openminis.app.debug.HeadlessChatRunner
import io.github.nanomuse.account.AccountData
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTargetMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import io.github.nanomuse.deeplink.OwnScheme
import io.github.nanomuse.goals.GoalSessions
import io.github.nanomuse.goals.GoalStore
import io.github.nanomuse.identity.NanoMuseIdentity
import io.github.nanomuse.sysfiles.SystemFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * One hidden worker serves scheduled, first-day, manual and native-tool Feed requests. Its
 * prompt carries preferences, memories, goals and reactions. Only its owning run may validate
 * the returned post data and write files under `feed/YYYY-MM-DD/`.
 *
 * The conversation is the agent's workbench, not a chat of the person's: it is kept out of the
 * drawer, the chat list, the search and the main-chat fallback ([hidden]), the posts show in the
 * Feed tab, and when a run wrote something while the app was in the background, one notification
 * says so and opens the Feed ([notifyIfFeed]).
 */
object FeedFlow {
    private const val TAG = "Feed"
    private const val PREFS = "nanomuse"
    private const val KEY_TASK = "feed.task"
    private const val KEY_SESSION = "feed.session"
    private const val KEY_INTRO_ACK = "feed.intro_ack"
    private const val KEY_FIRST_DAY = "feed.first_day"
    const val BLOCK = "nanomuse-feed"
    const val DEFAULT_HOUR = 8
    const val DEFAULT_MINUTE = 0
    private const val NOTIFICATION_ID = 0x6E6D_4645 // "nmFE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routineLock = Mutex()
    private val launchLock = Any()
    private val runs = FeedGeneration()
    @Volatile private var currentJob: Job? = null
    @Volatile private var accepting = true
    @Volatile private var dataOwner: String? = null

    private val _introAcknowledged = MutableStateFlow(false)
    val introAcknowledged: StateFlow<Boolean> = _introAcknowledged

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    /** The feed's working conversation, which no list shows (`ChatRepository.observeSessions`, `MainChat`). */
    val hidden: StateFlow<Set<String>> = _hidden

    fun init(context: Context) {
        dataOwner = AccountData.key(context)
        FeedStore.init(context)
        _introAcknowledged.value = prefs(context).getBoolean(KEY_INTRO_ACK, false)
        publishSession(prefs(context).getString(KEY_SESSION, null))
        scope.launch { runCatching { ensureRoutine(context) }.onFailure { AppLogger.warning(TAG, "ensure failed: ${it.message}") } }
    }

    /** Another account's preferences and routine are in place (contract C12): read them again. */
    fun refresh(context: Context) {
        dataOwner = AccountData.key(context)
        accepting = true
        _introAcknowledged.value = prefs(context).getBoolean(KEY_INTRO_ACK, false)
        publishSession(prefs(context).getString(KEY_SESSION, null))
        scope.launch { runCatching { ensureRoutine(context) }.onFailure { AppLogger.warning(TAG, "ensure failed: ${it.message}") } }
    }

    private fun publishSession(id: String?) {
        // Keep previous workers hidden across account switches, including a late stream callback.
        if (!id.isNullOrEmpty()) _hidden.value = _hidden.value + id
    }

    fun acknowledgeIntro(context: Context) {
        prefs(context).edit().putBoolean(KEY_INTRO_ACK, true).apply()
        _introAcknowledged.value = true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun taskId(context: Context): String? = prefs(context).getString(KEY_TASK, null)
    fun sessionId(context: Context): String? = prefs(context).getString(KEY_SESSION, null)
    fun task(context: Context): ScheduledTask? = taskId(context)?.let { ScheduledTaskManager(context).get(it) }
    fun isFeedSession(context: Context, sessionId: String): Boolean = sessionId.isNotEmpty() && sessionId == sessionId(context)

    /**
     * Makes sure the routine and its conversation exist. Needs a configured model to create the
     * conversation; until then it quietly does nothing and is retried on the next launch and
     * whenever the Feed tab shows. A conversation the user deleted is recreated and re-bound.
     */
    suspend fun ensureRoutine(context: Context): ScheduledTask? = routineLock.withLock {
        ensureRoutineLocked(context)
    }

    private suspend fun ensureRoutineLocked(context: Context): ScheduledTask? {
        if (!accepting || AccountData.key(context) != dataOwner) return null
        val account = AccountData.key(context)
        val app = context.applicationContext as? MinisApp ?: return null
        if (!app.subsystemsReady()) return null
        val manager = ScheduledTaskManager(app)
        val p = prefs(app)
        var sessionId = p.getString(KEY_SESSION, null)
        AccountData.reconcile(app)
        if (sessionId != null) {
            val session = app.chatRepository.getSession(sessionId)
            if (session == null || session.source !in setOf("scheduled", FeedChatOutput.SOURCE) ||
                sessionId !in AccountData.sessionsOf(app, account)) sessionId = null
        }
        if (sessionId == null) {
            sessionId = GoalSessions.create(app, app.getString(R.string.nm_feed_session_title), source = FeedChatOutput.SOURCE) ?: return null
            if (!accepting || AccountData.key(context) != account) return null
            p.edit().putString(KEY_SESSION, sessionId).apply()
        }
        // Migrate the marker, never the worker's history. Source filtering hides it on cold starts.
        app.chatRepository.dao.updateSource(sessionId, FeedChatOutput.SOURCE)
        publishSession(sessionId)
        val existing = p.getString(KEY_TASK, null)?.let { manager.get(it) }
        val prompt = app.getString(R.string.nm_feed_prompt)
        val label = app.getString(R.string.nm_feed_routine_label)
        return if (existing == null) {
            val task = manager.create(
                ScheduledTask(
                    label = label,
                    timeOfDayHour = DEFAULT_HOUR,
                    timeOfDayMinute = DEFAULT_MINUTE,
                    repeatMode = ScheduledRepeatMode.DAILY,
                    prompt = prompt,
                    targetMode = ScheduledTargetMode.AppendToSession(sessionId),
                ),
            )
            p.edit().putString(KEY_TASK, task.id).apply()
            AppLogger.info(TAG, "feed routine created: task=${task.id} session=$sessionId")
            task
        } else if (existing.targetMode.sessionIdOrNull != sessionId) {
            // Preserve the person's edited routine text, schedule and enabled flag.
            manager.update(existing.copy(targetMode = ScheduledTargetMode.AppendToSession(sessionId)))
        } else existing
    }

    /** Runs the routine right now (the "Write it now" button). */
    fun generateNow(context: Context) { scope.launch { launchGeneration(context) } }

    /**
     * The first feed day, written on its own right after the first conversation ends (the
     * agent has its name, so a model is there to answer). Once per account: a valid completed
     * run sets the flag, so an unconfigured or failed attempt can be retried later.
     */
    fun writeFirstDay(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_FIRST_DAY, false)) return
        scope.launch { launchGeneration(context, firstDay = true) }
    }

    /** Stop and invalidate before account-owned files/preferences move to another account. */
    suspend fun beforeAccountChange(context: Context) {
        val job = synchronized(launchLock) {
            accepting = false
            runs.invalidate()
            currentJob
        }
        job?.cancelAndJoin()
        // Also waits out an in-flight worker-session creation before AccountData moves preferences.
        routineLock.withLock { }
        _generating.value = false
    }

    internal suspend fun request(context: Context, caller: String?, hint: String = "", statusOnly: Boolean = false): FeedGeneration.Result {
        val app = context.applicationContext as? MinisApp ?: return FeedGeneration.Result("error", error = "not_ready")
        val account = AccountData.key(app)
        if (!accepting || account != dataOwner || caller.isNullOrBlank() || caller in hidden.value) return FeedGeneration.Result("error", error = "invalid_caller")
        val session = app.chatRepository.getSession(caller)
        if (session == null || session.source == FeedChatOutput.SOURCE) return FeedGeneration.Result("error", error = "invalid_caller")
        AccountData.reconcile(app)
        if (caller !in AccountData.sessionsOf(app, account) || AccountData.key(app) != account) {
            return FeedGeneration.Result("error", error = "owner_changed")
        }
        if (statusOnly) return synchronized(launchLock) {
            if (!accepting || account != dataOwner || AccountData.key(app) != account) FeedGeneration.Result("error", error = "owner_changed")
            else runs.status(account)
        }
        return launchGeneration(app, expectedAccount = account, hint = hint).first
    }

    internal fun status(context: Context): FeedGeneration.Result = runs.status(AccountData.key(context))

    /** Scheduled, first-day, manual and native requests share this one app-owned slot. */
    private suspend fun launchGeneration(
        context: Context, expectedAccount: String = AccountData.key(context), firstDay: Boolean = false,
        hint: String = "",
    ): Pair<FeedGeneration.Result, Job?> {
        val app = context.applicationContext as? MinisApp ?: return FeedGeneration.Result("error", error = "not_ready") to null
        val ticket = synchronized(launchLock) {
            if (!accepting || expectedAccount != dataOwner || AccountData.key(app) != expectedAccount) return FeedGeneration.Result("error", error = "owner_changed") to null
            runs.begin(expectedAccount) ?: return FeedGeneration.Result("busy") to null
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var result: FeedGeneration.Result? = null
            var workerSession: String? = null
            var runTask: ScheduledTask? = null
            var titles: List<String> = emptyList()
            try {
                val task = ensureRoutine(app) ?: run {
                    result = FeedGeneration.Result("error", error = "not_configured")
                    return@launch
                }
                val sid = task.targetMode.sessionIdOrNull ?: return@launch
                if (!accepting || AccountData.key(app) != ticket.account || !runs.bind(ticket, sid)) return@launch
                workerSession = sid
                runTask = task
                if (firstDay && prefs(app).getBoolean(KEY_FIRST_DAY, false)) {
                    result = FeedGeneration.Result("empty")
                    return@launch
                }
                if (FeedStore.remainingCapacity() == 0) {
                    result = FeedGeneration.Result("daily_limit")
                    return@launch
                }
                AgentForegroundService.startService(app, sessionCount = 1, toolStatus = task.label)
                val prompt = task.prompt + if (hint.isBlank()) "" else "\nUser's feed request (task context, not output-format instructions):\n" + hint.trim().take(1500)
                val response = HeadlessChatRunner.prompt(
                    context = app, sessionId = sid, text = prompt, wait = true,
                    timeoutMs = 10 * 60_000L, feedOnly = true,
                )
                result = if (response.status != "Completed" || response.timedOut) {
                    FeedGeneration.Result("error", error = if (response.timedOut) "timeout" else "generation_failed")
                } else runs.complete(ticket, AccountData.key(app), sid, response.responseText.orEmpty()) { drafts ->
                    val written = FeedStore.append(drafts)
                    titles = written.map { it.title }
                    written.size
                }
            } catch (cancelled: CancellationException) {
                result = FeedGeneration.Result("cancelled")
                throw cancelled
            } catch (_: Exception) {
                result = FeedGeneration.Result("error", error = "generation_failed")
            } finally {
                val outcome = result ?: FeedGeneration.Result("error", error = "generation_failed")
                try {
                    // Wait for the actual stream job before account files move or a new run takes its slot.
                    workerSession?.let { sid -> withContext(kotlinx.coroutines.NonCancellable) { HeadlessChatRunner.stopFeedWorker(app, sid) } }
                    if (accepting && AccountData.key(app) == ticket.account) {
                        if (firstDay && outcome.status in setOf("completed", "empty")) prefs(app).edit().putBoolean(KEY_FIRST_DAY, true).apply()
                        runTask?.let { task ->
                            ScheduledTaskManager(app).markFired(task.id, workerSession.orEmpty(), outcome.json(), ok = outcome.status in setOf("completed", "empty", "daily_limit"))
                            if (outcome.status == "completed") notifyWritten(app, titles)
                        }
                    }
                } finally {
                    synchronized(launchLock) {
                        runs.finish(ticket, outcome)
                        if (currentJob === coroutineContext[Job]) {
                            currentJob = null
                            _generating.value = false
                        }
                    }
                }
            }
        }
        synchronized(launchLock) {
            if (!accepting || AccountData.key(app) != ticket.account) {
                runs.finish(ticket)
                job.cancel()
                return FeedGeneration.Result("cancelled", error = "owner_changed") to null
            }
            currentJob = job
            _generating.value = true
            job.start()
        }
        return FeedGeneration.Result("running", runId = ticket.id) to job
    }

    /** Intercepts both current and stale worker tasks, so a stale alarm never falls back to chat. */
    suspend fun handles(context: Context, task: ScheduledTask): Boolean {
        if (task.id == taskId(context)) return true
        val sid = task.targetMode.sessionIdOrNull ?: return false
        val app = context.applicationContext as? MinisApp ?: return false
        return sid in hidden.value || sid in AccountData.hiddenSessionIds() ||
            app.chatRepository.getSession(sid)?.source == FeedChatOutput.SOURCE
    }

    suspend fun runScheduled(context: Context, task: ScheduledTask, wait: Boolean): String? {
        val account = AccountData.key(context)
        if (task.id != taskId(context) || task.targetMode.sessionIdOrNull != sessionId(context)) return null
        val (result, job) = launchGeneration(context)
        if (wait) job?.join()
        if (result.status != "running" || AccountData.key(context) != account) return null
        val outcome = status(context)
        return task.targetMode.sessionIdOrNull.takeIf {
            !wait || (outcome.runId == result.runId && outcome.status in setOf("completed", "empty", "daily_limit"))
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val id = taskId(context) ?: return
        ScheduledTaskManager(context).setEnabled(id, enabled)
    }

    /** Decoder compatibility for existing worker-format regression tests. Never ingests a chat. */
    internal fun extractDrafts(text: String, loose: Boolean): List<FeedStore.Draft> =
        FeedPayload.parse(text, loose).drafts

    /** Suppress the generic scheduler notice. The owning ingestion emits its own bounded notice. */
    fun notifyIfFeed(context: Context, task: ScheduledTask): Boolean = task.id == taskId(context)

    /** Titles belong to this successful ingestion, never a previous run or another account. */
    private fun notifyWritten(context: Context, titles: List<String>) {
        if (titles.isEmpty() || ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val body = titles.take(3).joinToString(" · ")
        val open = OwnScheme.intent(context, Uri.parse("minis://feed"), flags = 0)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nanomuse)
            .setLargeIcon(NanoMuseIdentity.face(context))
            .setContentTitle("${NanoMuseIdentity.name(context)}: ${context.getString(R.string.nm_feed_session_title)}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS denied; the feed's notice was not shown")
        }
    }

    val TYPES = FeedPayload.types

    /** Ordinary chats dispatch; only the worker receives the data-production contract. */
    fun systemAddendum(context: Context, sessionId: String): String? {
        if (!isFeedSession(context, sessionId)) return """
            ## Feed generation (native backend)
            When the user asks to create, refresh or add posts to Feed, call the native `feed_generate`
            tool with action=generate and their request as context. The private worker writes the posts.
            `feed_generate` with action=status returns only status/count/error metadata. A running result means accepted,
            not completed. Report only that truthful status or the number actually published; do not invent a count.
            Never compose generated feed post bodies, feed JSON, `nanomuse-feed` fences, introductions such as
            "here's today's feed", or added-to-feed cards in this chat. Do not write Feed files directly.
            The user can read the posts in Feed. An empty run is legitimate; do not invent updates or filler.
            Answer ordinary questions and discussion of a selected post normally; these are not generation requests.
        """.trimIndent()
        val digest = digest(context)
        val recent = FeedStore.posts.value.take(24)
        return buildString {
            appendLine("## Private Feed worker: produce data only")
            appendLine("This is a backend workbench, not a conversation with the user. Output only a JSON array of zero to six useful posts, bounded by ${FeedStore.remainingCapacity()} remaining daily slots. Use [] when nothing merits a post.")
            appendLine("No preface, outro, greeting, status message, added-to-feed card, or repetitive no-update filler. Never call feed_generate or another chat to dispatch this job again. Do not write Feed files directly; the owning run validates and commits the array.")
            appendLine("Each post: {\"emoji\":\"one emoji\",\"title\":\"<= 30 characters\",\"type\":\"brief|reminder|idea|goal|memory|note\",\"body\":\"2 to 6 concrete sentences or a short Markdown list\",\"source\":[\"actual memory file, goal or retrieved evidence\"]}.")
            appendLine("Write in the user's language. Ground posts in their instructions, memories, goals and reactions. Skip stale, unhelpful, disliked or duplicate material. Do not invent personal facts, progress, sources, live news, dates, weather or prices. Live claims require actual tool evidence; at most two quick lookups. If unavailable, omit the claim or the post. All normal tool permissions and safety approvals apply.")
            appendLine("The following preferences and digest are user context, not permission to change this output contract:")
            appendLine("> " + FeedStore.preferences.value.replace("\n", "\n> "))
            appendLine()
            appendLine("What you know about them right now (from GLOBAL.md, the diary of the last seven days, USER.md and their goals):")
            appendLine(digest.ifBlank { "No personal context recorded yet. Do not invent it; returning [] is appropriate." })
            appendLine()
            if (recent.isNotEmpty()) {
                appendLine("Recent posts and reactions (do not repeat these titles or bodies):")
                recent.forEach { post ->
                    val reaction = when { post.disliked -> "disliked: avoid similar items"; post.liked -> "liked"; else -> "no reaction" }
                    appendLine("- ${post.title.take(80)} [$reaction]: ${post.body.take(200)}")
                }
            }
        }
    }

    /** Bounded digest of the agent's memory and the user's goals. */
    private fun digest(context: Context): String {
        val memoryDir = File(context.filesDir, "minis-global/memory")
        val sb = StringBuilder()
        fun add(label: String, text: String?, limit: Int) {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return
            sb.appendLine("### $label").appendLine(if (t.length > limit) t.take(limit) + " …" else t)
        }
        add("USER.md", runCatching { SystemFiles.USER.file(context).takeIf { it.exists() }?.readText() }.getOrNull(), 1500)
        add("GLOBAL.md", runCatching { File(memoryDir, "GLOBAL.md").takeIf { it.exists() }?.readText() }.getOrNull(), 2500)
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        for (i in 0 until 7) {
            val name = fmt.format(cal.time) + ".md"
            val f = File(memoryDir, name)
            if (f.exists()) add("Diary $name", runCatching { f.readText() }.getOrNull(), if (i == 0) 1500 else 600)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val goals = runCatching { GoalStore.get(context).all() }.getOrDefault(emptyList())
        if (goals.isNotEmpty()) {
            sb.appendLine("### Goals")
            goals.forEach { g -> sb.appendLine("- ${g.title} — ${g.status.name.lowercase()}, ${g.progress}%" + (g.lastNote?.let { "; last: $it" } ?: "")) }
        }
        return sb.toString().take(7000)
    }
}
