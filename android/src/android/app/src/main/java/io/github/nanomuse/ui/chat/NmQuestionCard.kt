package io.github.nanomuse.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveHelp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import kotlinx.coroutines.delay

// nanoMuse: a single-select question flow, attached inline to an assistant message.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NmQuestionCard(
    questions: List<NmQuestion>,
    onSelect: (questionId: String, optionId: String) -> Unit,
    onSkip: (questionId: String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stateKey: String = questions.joinToString { it.id },
    onComplete: (Map<String, String>) -> Unit = {},
) {
    if (questions.isEmpty()) return
    var index by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    var selections by rememberSaveable(stateKey) { mutableStateOf(List(questions.size) { "" }) }
    var otherText by rememberSaveable(stateKey) { mutableStateOf(List(questions.size) { "" }) }
    var submitted by rememberSaveable(stateKey) { mutableStateOf(false) }
    val interactive = enabled && !submitted
    val page = index.coerceIn(0, questions.lastIndex)
    val scale = nmChatMotionScale()
    val skipped = stringResource(R.string.nm_card_skipped)
    fun advance() {
        if (selections.getOrNull(page).isNullOrEmpty()) onSkip(questions[page].id)
        onNext()
        if (page < questions.lastIndex) index = page + 1
        else {
            submitted = true
            onComplete(nmQuestionAnswers(questions, selections, otherText, skipped))
        }
    }
    NmAgentCardFrame(modifier, animate = enabled) {
        NmAgentCardHeader(Icons.Outlined.LiveHelp, stringResource(R.string.nm_card_questions))
        Spacer(Modifier.height(18.dp))
        AnimatedContent(targetState = page, label = "nmQuestionPage", transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(200)) { it * direction } + fadeIn(tween(200))) togetherWith
                (slideOutHorizontally(tween(200)) { -it * direction } + fadeOut(tween(200)))
        }) { current ->
            val question = questions[current]
            Column {
                Text(question.title, style = MaterialTheme.typography.titleMedium, color = NmCardInk)
                if (question.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(question.description, style = MaterialTheme.typography.bodyMedium, color = NmCardMuted)
                }
                Spacer(Modifier.height(16.dp))
                question.options.forEachIndexed { optionIndex, option ->
                    key(question.id, option.id) {
                        val chosen = selections.getOrNull(current) == option.id
                        val entrance = remember { Animatable(if (enabled && scale > 0f) 0f else 1f) }
                        LaunchedEffect(Unit) {
                            if (enabled && scale > 0f) delay((optionIndex * 60L * scale).toLong())
                            entrance.animateTo(1f, tween(200))
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).graphicsLayer {
                            alpha = entrance.value
                            translationY = (1f - entrance.value) * 5.dp.toPx()
                        }.clip(RoundedCornerShape(10.dp))
                            .background(if (chosen) NmCardInset else Color.Transparent)
                            .border(BorderStroke(1.dp, if (chosen) Color(0xFF707070) else Color.Transparent), RoundedCornerShape(10.dp))
                            .semantics { selected = chosen }
                            .clickable(enabled = interactive, role = Role.RadioButton) {
                                selections = selections.toMutableList().also { it[current] = option.id }
                                onSelect(question.id, option.id)
                            }.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).background(if (chosen) Color.White else NmCardInset, RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(('A'.code + optionIndex).toChar().toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium, color = if (chosen) Color.Black else NmCardMuted)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyMedium,
                                color = if (chosen) NmCardInk else NmCardMuted, modifier = Modifier.weight(1f))
                        }
                        AnimatedVisibility(chosen && option.allowsText) {
                            OutlinedTextField(value = otherText.getOrNull(current).orEmpty(), onValueChange = { value ->
                                otherText = otherText.toMutableList().also { it[current] = value }
                            }, enabled = interactive, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                label = { Text(stringResource(R.string.nm_card_other_answer)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = NmCardInk, unfocusedTextColor = NmCardInk,
                                    cursorColor = NmCardInk, focusedBorderColor = NmCardInk,
                                    unfocusedBorderColor = NmCardOutline, focusedLabelColor = NmCardMuted,
                                    unfocusedLabelColor = NmCardMuted))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page > 0) TextButton(enabled = interactive, onClick = {
                index = page - 1
                onPrevious()
            }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(stringResource(R.string.nm_card_previous), color = NmCardMuted,
                    style = MaterialTheme.typography.labelMedium)
            }
            TextButton(enabled = interactive, onClick = {
                selections = selections.toMutableList().also { it[page] = "" }
                onSkip(questions[page].id)
                onNext()
                if (page < questions.lastIndex) index = page + 1
                else {
                    submitted = true
                    onComplete(nmQuestionAnswers(questions, selections, otherText, skipped))
                }
            }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.nm_card_skip), color = NmCardMuted)
            }
            Button(onClick = ::advance, enabled = interactive, colors = nmCardButtonColors(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.nm_card_next))
            }
        }
    }
}
