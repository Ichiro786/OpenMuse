package io.github.nanomuse.ui.feed

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTaskStore
import com.openminis.app.ui.markdown.MarkdownText
import com.openminis.app.ui.theme.ChatColors
import io.github.nanomuse.feed.FeedFlow
import io.github.nanomuse.feed.FeedPost
import io.github.nanomuse.feed.FeedStore
import io.github.nanomuse.ui.home.MuseTones
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The header's sliders button and the tab live in different composables; this is the wire. */
object FeedUi {
    val settingsOpen = MutableStateFlow(false)
}


/** nanoMuse: borderless monochrome feed, with an editable prompt and labeled sample posts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTab(
    header: @Composable () -> Unit,
    onDiscuss: (FeedPost) -> Unit,
    onEditRoutine: (String) -> Unit,
) {
    val context = LocalContext.current
    val posts by FeedStore.posts.collectAsState()
    val generating by FeedFlow.generating.collectAsState()
    val settingsOpen by FeedUi.settingsOpen.collectAsState()
    val preferences by FeedStore.preferences.collectAsState()
    val model: NmFeedViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var infoPost by remember { mutableStateOf<FeedPost?>(null) }
    var scheduleOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { FeedFlow.ensureRoutine(context) } }
    LaunchedEffect(preferences) { if (!model.instructionsOpen.value) model.prompt.value = preferences }
    LaunchedEffect(settingsOpen) {
        if (settingsOpen) { model.openInstructions(preferences); FeedUi.settingsOpen.value = false }
    }
    val samplePosts = if (posts.isEmpty()) listOf(
        FeedPost(todayKey(), 1, stringResource(R.string.nm_feed_sample_skills_title), "brief", "",
            stringResource(R.string.nm_feed_sample_skills_body), emptyList(), System.currentTimeMillis(),
            model.previewReactions["skills"] == 1, java.io.File("sample-skills.md"), model.previewReactions["skills"] == -1),
        FeedPost(todayKey(), 2, stringResource(R.string.nm_feed_sample_voice_title), "reminder", "",
            stringResource(R.string.nm_feed_sample_voice_body), emptyList(), System.currentTimeMillis(),
            model.previewReactions["voice"] == 1, java.io.File("sample-voice.md"), model.previewReactions["voice"] == -1),
    ) else emptyList()
    val displayed = if (posts.isEmpty()) samplePosts else posts
    val days = displayed.groupBy { it.day }.toList()

    io.github.nanomuse.ui.components.NmMonochrome {
    LazyColumn(modifier = Modifier.fillMaxSize().background(Color.Black), contentPadding = PaddingValues(bottom = 28.dp)) {
        item(key = "header") { Box(Modifier.clickable { model.openInstructions(preferences) }) { header() } }
        item(key = "instructions") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Surface(shape = RoundedCornerShape(50), color = Color(0xFF202020),
                    modifier = Modifier.fillMaxWidth().clickable { model.openInstructions(preferences) }) {
                    Text(preferences, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = 15.sp, color = Color(0xFFAAAAAA), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { scheduleOpen = true }) {
                        Text(stringResource(R.string.nm_feed_schedule), color = Color(0xFFAAAAAA))
                    }
                }
                if (posts.isEmpty()) {
                    Text(stringResource(R.string.nm_feed_sample_label), color = Color(0xFF888888), fontSize = 12.sp)
                    TextButton(enabled = !generating, onClick = { FeedFlow.acknowledgeIntro(context); FeedFlow.generateNow(context) }) {
                        Text(stringResource(if (generating) R.string.nm_feed_generating else R.string.nm_feed_write_now), color = Color.White)
                    }
                }
            }
        }
        days.forEach { (day, dayPosts) ->
            item(key = "day-$day") { DayTitle(dayLabel(day, dayPosts.first().createdAt)) }
            items(dayPosts, key = { it.id }) { post ->
                val preview = posts.isEmpty()
                val previewKey = if (post.index == 1) "skills" else "voice"
                NmFeedPost(post, onLike = {
                    if (preview) model.previewReactions[previewKey] = if (post.liked) 0 else 1
                    else FeedStore.setLiked(post, !post.liked)
                }, onDislike = {
                    if (preview) model.previewReactions[previewKey] = if (post.disliked) 0 else -1
                    else FeedStore.setDisliked(post, !post.disliked)
                }, onDiscuss = { onDiscuss(post) }, onInfo = { infoPost = post },
                    modifier = Modifier.animateItem(), preview = preview)
            }
        }
    }
    if (model.instructionsOpen.value) NmFeedInstructionsDialog(model.prompt.value,
        onCancel = model::closeInstructions,
        onSave = { model.saveInstructions(it); FeedFlow.acknowledgeIntro(context) })
    if (scheduleOpen) FeedSettingsSheet(onDismiss = { scheduleOpen = false }, onEditRoutine = onEditRoutine)

    infoPost?.let { post ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { infoPost = null },
            sheetState = sheetState,
            containerColor = ChatColors.background,
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text(post.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(12.dp))
                InfoRow(stringResource(R.string.nm_feed_info_type), typeLabel(post.type))
                InfoRow(stringResource(R.string.nm_feed_info_written), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(post.createdAt)))
                if (post.source.isNotEmpty()) InfoRow(stringResource(R.string.nm_feed_info_source), post.source.joinToString("\n"))
                if (!post.file.name.startsWith("sample-")) InfoRow(stringResource(R.string.nm_feed_info_file), "minis-global/nanomuse/feed/${post.day}/${"%02d".format(Locale.ROOT, post.index)}.md")
                Spacer(Modifier.height(16.dp))
                if (!post.file.name.startsWith("sample-")) TextButton(onClick = { FeedStore.delete(post); infoPost = null }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Color(0xFFAAAAAA), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.nm_feed_delete), color = Color(0xFFAAAAAA))
                }
            }
        }
    }
    } // nanoMuse: scoped neutral feed surfaces
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 13.sp, color = ChatColors.secondaryText, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DayTitle(text: String) {
    Text(
        text = text,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
    )
}

/** White card frame shared by every card on the page. */
@Composable
private fun FeedCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1C1C1C),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .animateContentSize(),
    ) { content() }
}

/** Sliders sheet: the steering sentence, the routine's switch and time, "Write it now". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedSettingsSheet(onDismiss: () -> Unit, onEditRoutine: (String) -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs by FeedStore.preferences.collectAsState()
    var text by remember(prefs) { mutableStateOf(prefs) }
    val taskStore = remember { ScheduledTaskStore(context) }
    val tasks by taskStore.observe().collectAsState(initial = taskStore.all())
    val task = remember(tasks) { FeedFlow.taskId(context)?.let { id -> tasks.firstOrNull { it.id == id } } }
    val generating by FeedFlow.generating.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = ChatColors.background) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.nm_feed_settings_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.nm_feed_intro_desc), fontSize = 13.sp, lineHeight = 18.sp, color = ChatColors.secondaryText)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color(0xFF333333),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
            )
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF252525), modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.nm_feed_routine_switch), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            val time = task?.let { "%02d:%02d".format(it.timeOfDayHour, it.timeOfDayMinute) }
                            Text(
                                if (time != null) stringResource(R.string.nm_feed_routine_time, time) else stringResource(R.string.nm_feed_routine_needs_model),
                                fontSize = 12.sp,
                                color = ChatColors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = task != null) { task?.let { onEditRoutine(it.id) } },
                            )
                        }
                        Switch(checked = task?.enabled == true, enabled = task != null, onCheckedChange = { FeedFlow.setEnabled(context, it) })
                    }
                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 14.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !generating) { FeedFlow.generateNow(context); onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (generating) R.string.nm_feed_generating else R.string.nm_feed_write_now),
                            fontSize = 15.sp,
                            color = if (generating) ChatColors.secondaryText else Color.White,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { FeedStore.savePreferences(text); FeedFlow.acknowledgeIntro(context); onDismiss() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text(stringResource(R.string.nm_feed_save), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── labels ─────────────────────────────────────────────────────────────────

private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** "Wednesday morning" for today, "Yesterday" for the day before, "Monday · Sep 22" further back. */
@Composable
private fun dayLabel(day: String, firstPostAt: Long): String {
    val weekdays = stringArrayResource(R.array.nm_weekdays)
    val cal = Calendar.getInstance()
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day) }.getOrNull() ?: Date(firstPostAt)
    cal.time = date
    val weekday = weekdays[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] // Calendar: SUNDAY=1 → index 6
    val today = todayKey()
    return when {
        day == today -> stringResource(R.string.nm_feed_today_short)
        isYesterday(cal) -> stringResource(R.string.nm_time_yesterday)
        else -> stringResource(R.string.nm_feed_day_past, weekday, DateFormat.getDateInstance(DateFormat.MEDIUM).format(date))
    }
}

private fun isYesterday(cal: Calendar): Boolean {
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return y.get(Calendar.YEAR) == cal.get(Calendar.YEAR) && y.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun typeLabel(type: String): String = stringResource(
    when (type) {
        "brief" -> R.string.nm_feed_type_brief
        "reminder" -> R.string.nm_feed_type_reminder
        "idea" -> R.string.nm_feed_type_idea
        "goal" -> R.string.nm_feed_type_goal
        "memory" -> R.string.nm_feed_type_memory
        else -> R.string.nm_feed_type_note
    },
)

private fun defaultEmoji(type: String): String = when (type) {
    "brief" -> "📰"
    "reminder" -> "⏰"
    "idea" -> "💡"
    "goal" -> "🎯"
    "memory" -> "🧠"
    else -> "📝"
}
