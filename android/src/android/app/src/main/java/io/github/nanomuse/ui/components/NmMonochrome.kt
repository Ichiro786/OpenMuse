package io.github.nanomuse.ui.components

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// nanoMuse: neutral colors are scoped to the new feed and voice surfaces.
val LocalNmMonochrome = compositionLocalOf { false }

@Composable
fun NmMonochrome(content: @Composable () -> Unit) {
    val grey = Color(0xFFAAAAAA)
    CompositionLocalProvider(LocalNmMonochrome provides true,
        LocalTextSelectionColors provides TextSelectionColors(Color.White, Color.White.copy(alpha = 0.25f))) {
        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color.White, onPrimary = Color.Black, secondary = grey, onSecondary = Color.Black,
            tertiary = grey, onTertiary = Color.Black, background = Color.Black, onBackground = Color.White,
            surface = Color(0xFF1C1C1C), onSurface = Color.White, onSurfaceVariant = grey,
            surfaceVariant = Color(0xFF252525), outline = Color(0xFF555555), outlineVariant = Color(0xFF303030),
            primaryContainer = Color(0xFF333333), onPrimaryContainer = Color.White,
            secondaryContainer = Color(0xFF333333), onSecondaryContainer = Color.White,
            tertiaryContainer = Color(0xFF333333), onTertiaryContainer = Color.White,
            inverseSurface = Color.White, inverseOnSurface = Color.Black, inversePrimary = Color.Black,
            surfaceDim = Color.Black, surfaceBright = Color(0xFF333333), surfaceTint = Color.White,
            surfaceContainerLowest = Color(0xFF101010), surfaceContainerLow = Color(0xFF1C1C1C),
            surfaceContainer = Color(0xFF202020), surfaceContainerHigh = Color(0xFF252525),
            surfaceContainerHighest = Color(0xFF2B2B2B),
            error = grey, onError = Color.Black, errorContainer = Color(0xFF333333), onErrorContainer = Color.White), typography = MaterialTheme.typography, content = content)
    }
}
