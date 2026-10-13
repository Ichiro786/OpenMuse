package io.github.nanomuse.ui.chat

import androidx.compose.animation.core.Easing
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

// nanoMuse: reference-timed spring curves; motion durations follow the requested menu choreography.
internal val NmMenuBloom = Easing { t ->
    if (t >= 1f) 1f else 1f - exp(-8f * t) * (cos(11f * t) + 8f / 11f * sin(11f * t))
}
internal val NmMenuSettle = Easing { t ->
    ((1f - (1f + 8f * t) * exp(-8f * t)) / (1f - 9f * exp(-8f))).coerceIn(0f, 1f)
}

// nanoMuse: measure recent UI-frame misses at the display's refresh rate, not a fixed 60 Hz.
internal class NmMenuFrameBudget(private val frameNanos: Long) {
    private val samples = ArrayDeque<Boolean>()
    private var warmup = 4
    fun record(elapsedNanos: Long): Boolean {
        if (elapsedNanos <= 0L) return false
        if (warmup > 0) { warmup--; return false }
        samples.addLast(elapsedNanos > frameNanos * 1.75)
        if (samples.size > 32) samples.removeFirst()
        return samples.size >= 16 && samples.count { it } >= 8
    }
}
