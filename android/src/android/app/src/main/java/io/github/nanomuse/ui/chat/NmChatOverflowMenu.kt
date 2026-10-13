package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.openminis.app.R
import io.github.nanomuse.ui.components.NmMonochrome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// nanoMuse: a grouped, draggable chat menu with one exit path before each action.
enum class NmChatMenuAction { NewChat, Rename, Model, Terminal, Browser, Files, Skills, Mcps, Memories, Tokens }

@Composable
fun NmChatOverflowMenu(modelName: String, autoCompact: Boolean, skillsAvailable: Boolean,
    mcpsAvailable: Boolean, memoriesAvailable: Boolean, onAction: (NmChatMenuAction) -> Unit,
    onAutoCompact: (Boolean) -> Unit, onDismiss: () -> Unit, onHiddenDebug: (() -> Unit)? = null) {
    val slide = remember { Animatable(1f) }
    val scrim = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<NmChatMenuAction?>(null) }
    var drag by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    fun close(next: NmChatMenuAction? = null) { if (!closing) { settleJob?.cancel(); action = next; closing = true } }
    fun release(velocity: Float) {
        if (drag > threshold || velocity > threshold * 10f) close()
        else {
        settleJob?.cancel()
        settleJob = scope.launch {
            val settle = Animatable(drag)
            settle.animateTo(0f, tween(200, easing = NmMenuSettle)) { drag = value }
        }
        }
    }
    val latestDismiss = rememberUpdatedState(onDismiss)
    val latestAction = rememberUpdatedState(onAction)
    LaunchedEffect(closing) {
        coroutineScope {
            launch { slide.animateTo(if (closing) 1f else 0f, tween(if (closing) 200 else 300, easing = NmMenuSettle)) }
            launch { scrim.animateTo(if (closing) 0f else 0.5f, tween(200)) }
        }
        if (closing) { latestDismiss.value(); action?.let { latestAction.value(it) } }
    }
    val nested = remember(scroll, threshold) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            settleJob?.cancel()
            if ((available.y > 0 && scroll.value == 0) || drag > 0f) {
                val used = if (available.y < 0) maxOf(available.y, -drag) else available.y
                drag = (drag + used).coerceAtLeast(0f)
                return Offset(0f, used)
            }
            return Offset.Zero
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (drag <= 0f) return Velocity.Zero
            release(available.y)
            return available
        }
    } }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) { window?.setDimAmount(0f); onDispose { } }
        val title = stringResource(R.string.nm_menu_title)
        val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.94f
        NmMonochrome {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim.value))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null) { close() }.statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                Surface(Modifier.fillMaxWidth().heightIn(max = maxHeight).graphicsLayer {
                    translationY = size.height * slide.value + drag
                }.semantics { paneTitle = title; dismiss { close(); true } }
                    .pointerInput(Unit) { detectTapGestures(onTap = { }) },
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = Color(0xFF1C1C1C)) {
                    Column {
                        var taps by remember { mutableIntStateOf(0) }
                        var lastTap by remember { mutableLongStateOf(0L) }
                        Box(Modifier.fillMaxWidth().height(32.dp).draggable(
                            state = rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) },
                            orientation = Orientation.Vertical, onDragStarted = { settleJob?.cancel() }, onDragStopped = { release(it) })
                            .clickable(enabled = onHiddenDebug != null) {
                                val now = android.os.SystemClock.uptimeMillis()
                                taps = if (now - lastTap <= 700L) taps + 1 else 1
                                lastTap = now
                                if (taps >= 7) { taps = 0; onHiddenDebug?.invoke() }
                            }.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp, 4.dp).background(Color(0xFF666666), RoundedCornerShape(50)))
                        }
                        Column(Modifier.weight(1f, fill = false).nestedScroll(nested).verticalScroll(scroll)
                            .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            fun choose(a: NmChatMenuAction) = close(a)
                            NmOverflowRow(Icons.Outlined.ChatBubbleOutline, R.string.nm_menu_new_chat, onClick = { choose(NmChatMenuAction.NewChat) })
                            NmOverflowRow(Icons.Outlined.Edit, R.string.nm_chat_menu_rename, onClick = { choose(NmChatMenuAction.Rename) })
                            NmOverflowRow(Icons.Outlined.Tune, R.string.nm_chat_menu_model, subtitle = modelName, onClick = { choose(NmChatMenuAction.Model) })
                            NmOverflowDivider()
                            NmOverflowRow(Icons.Outlined.Terminal, R.string.nm_menu_open_terminal, onClick = { choose(NmChatMenuAction.Terminal) })
                            NmOverflowRow(Icons.Outlined.Language, R.string.nm_menu_open_browser, onClick = { choose(NmChatMenuAction.Browser) })
                            NmOverflowRow(Icons.Outlined.Folder, R.string.nm_menu_chat_files, onClick = { choose(NmChatMenuAction.Files) })
                            NmOverflowDivider()
                            NmOverflowRow(Icons.Outlined.Build, R.string.nm_menu_session_skills, enabled = skillsAvailable, onClick = { choose(NmChatMenuAction.Skills) })
                            NmOverflowRow(Icons.Outlined.Extension, R.string.nm_menu_session_mcps, enabled = mcpsAvailable, onClick = { choose(NmChatMenuAction.Mcps) })
                            NmOverflowRow(Icons.Outlined.Psychology, R.string.nm_menu_session_memories, enabled = memoriesAvailable, onClick = { choose(NmChatMenuAction.Memories) })
                            NmOverflowDivider()
                            NmOverflowRow(Icons.Outlined.DataUsage, R.string.nm_menu_token_usage, onClick = { choose(NmChatMenuAction.Tokens) })
                            NmOverflowRow(Icons.Outlined.Compress, R.string.nm_menu_auto_compact,
                                onClick = { onAutoCompact(!autoCompact) }, trailing = {
                                    Switch(checked = autoCompact, onCheckedChange = onAutoCompact,
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Black,
                                            checkedTrackColor = Color.White, uncheckedThumbColor = Color(0xFFAAAAAA),
                                            uncheckedTrackColor = Color(0xFF333333), uncheckedBorderColor = Color(0xFF555555)))
                                })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NmOverflowDivider() = HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White.copy(alpha = 0.1f))

@Composable
private fun NmOverflowRow(icon: ImageVector, label: Int, subtitle: String? = null, enabled: Boolean = true,
    onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(56.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val ink = if (enabled) Color.White else Color(0xFF777777)
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), color = ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}
