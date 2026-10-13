package io.github.nanomuse.ui.chat

// nanoMuse: selecting a voice skill preserves dictated text and replaces an earlier skill prefix.
internal fun nmInsertVoiceSkill(input: String, name: String, installedNames: List<String>): String {
    if (name.isBlank()) return input
    val previous = installedNames.sortedByDescending { it.length }.firstOrNull {
        input == "/$it" || input.startsWith("/$it ")
    }
    val body = when {
        previous != null -> input.removePrefix("/$previous").removePrefix(" ")
        input == "/" -> ""
        else -> input
    }
    return "/$name $body"
}
