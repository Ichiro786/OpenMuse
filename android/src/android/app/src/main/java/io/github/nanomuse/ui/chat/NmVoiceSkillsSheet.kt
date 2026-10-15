package io.github.nanomuse.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.SkillRepository
import io.github.nanomuse.ui.components.NmMonochrome

// nanoMuse: installed, session-enabled skills use the composer's existing slash-command resolver.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NmVoiceSkillsSheet(skills: List<SkillRepository.Skill>, onSelect: (SkillRepository.Skill) -> Unit,
    onDismiss: () -> Unit) {
    NmMonochrome {
        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1C1C),
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text(stringResource(R.string.nm_voice_skills), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Color.White)
            if (skills.isEmpty()) Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.nm_voice_no_skills), color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.nm_voice_skills_hint), color = Color(0xFFAAAAAA))
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(skills, key = { it.id }) { skill ->
                    Column(Modifier.fillMaxWidth().clickable { onSelect(skill) }.padding(horizontal = 24.dp, vertical = 14.dp)) {
                        Text(skill.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
                        if (skill.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(skill.description, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAAAAA),
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
