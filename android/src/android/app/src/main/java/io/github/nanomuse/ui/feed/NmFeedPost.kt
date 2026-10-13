package io.github.nanomuse.ui.feed

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.ui.markdown.MarkdownText
import io.github.nanomuse.feed.FeedPost
import io.github.nanomuse.ui.components.NmMonochrome

// nanoMuse: borderless feed posts with neutral media, real reactions, and discussion actions.
internal data class NmFeedBody(val markdown: String, val image: String?)
internal fun nmFeedBody(body: String): NmFeedBody {
    val pattern = Regex("!\\[[^\\r\\n]*?\\]\\((https?://[^\\s)]+)(?:\\s+\"[^\"]*\")?\\)")
    var offset = 0
    var fence: Char? = null
    body.splitToSequence('\n').forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val marker = trimmed.first()
            if (fence == null) fence = marker else if (fence == marker) fence = null
        } else if (fence == null) {
            val image = pattern.find(line)
            if (image != null) {
                val start = offset + image.range.first
                val end = offset + image.range.last + 1
                return NmFeedBody(body.removeRange(start, end).trim(), image.groupValues[1])
            }
        }
        offset += line.length + 1
    }
    return NmFeedBody(body, null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NmFeedPost(post: FeedPost, onLike: () -> Unit, onDislike: () -> Unit, onDiscuss: () -> Unit,
    onInfo: () -> Unit, modifier: Modifier = Modifier, preview: Boolean = false) {
    val body = remember(post.body) { nmFeedBody(post.body) }
    val muted = Color(0xFF9E9E9E)
    NmMonochrome {
        Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).background(Color(0xFF202020), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    val icon = when (post.type) {
                        "brief" -> Icons.Outlined.Article
                        "reminder" -> Icons.Outlined.MicNone
                        "idea" -> Icons.Outlined.Lightbulb
                        "goal" -> Icons.Outlined.Flag
                        "memory" -> Icons.Outlined.BookmarkBorder
                        else -> Icons.Outlined.Description
                    }
                    Icon(icon, contentDescription = null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(post.title, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.padding(start = 52.dp)) {
                MarkdownText(body.markdown, color = Color(0xFFCCCCCC),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 23.sp))
            }
            body.image?.let { url ->
                Spacer(Modifier.height(14.dp))
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            }
            if (preview && post.type == "brief" && body.image == null) {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF333333), Color(0xFF151515)))),
                    contentAlignment = Alignment.Center) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Extension, contentDescription = null, tint = Color(0xFFCCCCCC), modifier = Modifier.size(56.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(post.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            lineHeight = 27.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(Modifier.fillMaxWidth().padding(start = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.Center) {
                IconButton(onClick = onLike, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ThumbUp, contentDescription = stringResource(R.string.nm_feed_like),
                        tint = if (post.liked) Color.White else muted, modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = onDislike, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ThumbDown, contentDescription = stringResource(R.string.nm_feed_dislike),
                        tint = if (post.disliked) Color.White else muted, modifier = Modifier.size(21.dp))
                }
                Row(Modifier.heightIn(min = 40.dp).clickable(onClick = onDiscuss).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.nm_feed_discuss), color = muted, fontSize = 14.sp)
                }
                Row(Modifier.weight(1f).widthIn(min = 76.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (preview) stringResource(R.string.nm_feed_sample_label) else
                        DateUtils.getRelativeTimeSpanString(post.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE).toString(), color = muted, fontSize = 14.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onInfo, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.nm_feed_info_title), tint = muted, modifier = Modifier.size(21.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, color = Color(0xFF151515))
        }
    }
}
