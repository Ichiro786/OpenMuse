package io.github.nanomuse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NmMessageReactions(state: NmReactionState, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    val current = state.counts.filterValues { it > 0 }
    val retained = remember { mutableStateMapOf<String, Int>() }
    SideEffect { current.forEach { (emoji, count) -> retained[emoji] = count } }
    LaunchedEffect(current) {
        delay(160)
        retained.keys.toList().filter { it !in current }.forEach { retained.remove(it) }
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        retained.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).forEach { (emoji, count) ->
            key(emoji) {
                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appeared = true }
                AnimatedVisibility(appeared && emoji in current,
                    enter = fadeIn(tween(200)) + scaleIn(tween(200, easing = NmMenuBloom), initialScale = 0.5f),
                    exit = fadeOut(tween(150))) {
                    val shape = RoundedCornerShape(12.dp)
                    val label = stringResource(R.string.nm_reaction_toggle, emoji, count)
                    Row(Modifier.padding(top = 4.dp).height(28.dp).clip(shape)
                        .background(Color(0xFF292929))
                        .border(1.dp, Color.White.copy(alpha = if (emoji in state.myReactions) 0.25f else 0.08f), shape)
                        .clickable { onToggle(emoji) }.semantics { contentDescription = label }
                        .padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        // nanoMuse: reaction glyphs retain their system emoji colors.
                        Text(emoji, fontSize = 16.sp)
                        Text(count.toString(), fontSize = 12.sp, color = Color(0xFFCCCCCC))
                    }
                }
            }
        }
    }
}
