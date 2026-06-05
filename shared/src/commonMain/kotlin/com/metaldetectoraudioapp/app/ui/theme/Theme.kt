package com.metaldetectoraudioapp.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Shared Material 3 theme for Android, Desktop and Web. Built on the "sky &
 * field" brand palette (see [SkyAndFieldLightColors] / [SkyAndFieldDarkColors]).
 * Android dynamic colors (Material You) are applied as an override in the
 * Android-specific entry point.
 */
@Composable
fun MetalDetectorAudioTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) SkyAndFieldDarkColors else SkyAndFieldLightColors,
        content = content,
    )
}
