package com.metaldetectoraudioapp.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ── Brand palette: "sky & field" ────────────────────────────────────────────
// Shared identity with the marketing site (web/landing/styles.css) and the app
// icon, so the product reads as one thing across every surface. Sky carries the
// primary brand (UI chrome, controls); field is the supporting accent; gold is
// the "target found" highlight pulled from the icon.
private val Sky900 = Color(0xFF153E4E)
private val Sky700 = Color(0xFF2F6F8B)
private val Sky500 = Color(0xFF65A9C7)
private val Sky100 = Color(0xFFC6E6F1)

private val Field800 = Color(0xFF2F5F4B)
private val Field700 = Color(0xFF3E6F42)
private val Field200 = Color(0xFFCFE3C3)

private val GoldDeep = Color(0xFF8A6A12)
private val Gold = Color(0xFFE5B756)
private val GoldContainer = Color(0xFFFBE7B8)

val SkyAndFieldLightColors = lightColorScheme(
    primary = Sky700,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Sky100,
    onPrimaryContainer = Color(0xFF071F29),
    secondary = Field700,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Field200,
    onSecondaryContainer = Color(0xFF12281A),
    tertiary = GoldDeep,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = GoldContainer,
    onTertiaryContainer = Color(0xFF2A1F00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAF4),
    onBackground = Color(0xFF17201B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17201B),
    surfaceVariant = Color(0xFFDCE6DD),
    onSurfaceVariant = Color(0xFF49544C),
    surfaceTint = Sky700,
    outline = Color(0xFF7A857C),
    outlineVariant = Color(0xFFD6DED1),
    inverseSurface = Color(0xFF2B322D),
    inverseOnSurface = Color(0xFFEFF2EB),
    inversePrimary = Sky500,
)

val SkyAndFieldDarkColors = darkColorScheme(
    primary = Sky500,
    onPrimary = Color(0xFF053443),
    primaryContainer = Color(0xFF1E4F62),
    onPrimaryContainer = Sky100,
    secondary = Color(0xFF9FD09A),
    onSecondary = Color(0xFF0C3818),
    secondaryContainer = Field800,
    onSecondaryContainer = Field200,
    tertiary = Gold,
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF5C4710),
    onTertiaryContainer = GoldContainer,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1714),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF141B17),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF3F4A43),
    onSurfaceVariant = Color(0xFFBFC9BF),
    surfaceTint = Sky500,
    outline = Color(0xFF899389),
    outlineVariant = Color(0xFF3F4A43),
    inverseSurface = Color(0xFFDFE4DD),
    inverseOnSurface = Color(0xFF2B322D),
    inversePrimary = Sky700,
)

/**
 * Semantic colors for detection state. These deliberately live outside the M3
 * scheme: TARGET / JUNK / AMBIENT carry fixed meaning, so they stay constant
 * rather than drifting with light/dark — or, on Android, with Material You.
 */
object DetectionColors {
    val Target = Color(0xFF2E7D46)
    val Junk = Color(0xFFC0392B)
    val Ambient = Color(0xFF5E6B62)
    val StickyBanner = Color(0xFF1B5E20)
    val Recording = Color(0xFFC62828)
    val AcceleratorGpu = Sky700
}
