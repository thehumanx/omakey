package dev.omakey.app.keyboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.omakey.core.theme.CustomThemePreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.systemAccentColor

/** Turns whatever [ThemeRepository][dev.omakey.core.theme.ThemeRepository] has stored into what
 * should actually be rendered — the one place both `KeyboardRoot` and `SettingsActivity` resolve
 * "Follow system" (a sentinel [Presets.Auto] entry, not a separate boolean layered on top of a
 * specific preset — matches how the OS's own light/dark setting works: one choice among Light/
 * Dark/System, not an independent toggle) and "pick accent color from system" (genuinely
 * orthogonal — you can want a fixed preset but still have the accent follow the device palette).
 * Composable so it can use [isSystemInDarkTheme], which already recomposes automatically on a
 * system light/dark change — no manual `ComponentCallbacks`/config-change listener needed. */
@Composable
fun resolveEffectiveTheme(stored: OmakeyTheme, useSystemAccent: Boolean): OmakeyTheme {
    val systemDark = isSystemInDarkTheme()
    var effective = if (stored.id == Presets.Auto.id) {
        if (systemDark) Presets.Dark else Presets.Light
    } else {
        stored
    }
    // Only Light/Dark/Auto/Accent — a custom theme's colors are exactly what the user picked in
    // the HSV editor, and overriding one of those 4 with the system accent would undo that choice
    // silently rather than respect it.
    val isCustomTheme = stored.id.startsWith(CustomThemePreferences.ID_PREFIX)
    if (useSystemAccent && !isCustomTheme) {
        val context = LocalContext.current
        systemAccentColor(context)?.let { accent ->
            effective = effective.copy(spacebarAccentColor = accent, keyBackgroundPressed = accent)
        }
    }
    return effective
}
