package io.github.nanomuse.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale

/** Use Compose's observed system animation scale, including animations disabled. */
@Composable
internal fun nmChatMotionScale(): Float =
    (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f)
        .coerceAtLeast(0f)
