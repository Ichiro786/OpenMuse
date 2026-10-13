package io.github.nanomuse.ui.feed

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import io.github.nanomuse.feed.FeedStore

// nanoMuse: ephemeral dialog state; the existing feed preference file remains the prompt source.
class NmFeedViewModel : ViewModel() {
    val instructionsOpen = mutableStateOf(false)
    val prompt = mutableStateOf(FeedStore.preferences.value)
    val previewReactions = mutableStateMapOf<String, Int>()

    fun openInstructions(value: String) { prompt.value = value; instructionsOpen.value = true }
    fun closeInstructions() { instructionsOpen.value = false }
    fun saveInstructions(value: String) {
        FeedStore.savePreferences(value)
        prompt.value = FeedStore.preferences.value
        instructionsOpen.value = false
    }
}
