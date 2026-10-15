package io.github.nanomuse.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.openminis.app.ui.chat.*

// nanoMuse: user text joins the existing selection controller only for the new message menu.
@Composable
internal fun NmSelectableUserText(messageId: String, text: String, color: Color, style: TextStyle,
    modifier: Modifier = Modifier) {
    val controller = LocalMinisSelectionController.current
    val id = remember(messageId) { TextShardId(messageId, "user") }
    var result by remember { mutableStateOf<TextLayoutResult?>(null) }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val shard = remember(id, text, result) {
        result?.let { buildTextShard(id, text, it, { coordinates[0] }) }
    }
    RegisterSelectionShard(shard)
    SideEffect { controller?.rememberMessageMarkdown(messageId, text) }
    val selection = controller?.selection?.value
    Text(text, color = color, style = style, onTextLayout = { result = it },
        modifier = modifier.onGloballyPositioned { coordinates[0] = it }.drawBehind {
            val layout = result
            if (layout != null && selection != null)
                drawSelectionForShard(id, layout, selection, controller, Color.Gray.copy(alpha = 0.4f))
        })
}
