package dev.omakey.app.keyboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.omakey.core.theme.CustomThemePreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.systemAccent
import dev.omakey.core.theme.systemDynamicTheme

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
    val context = LocalContext.current
    var effective = when (stored.id) {
        Presets.Auto.id -> if (systemDark) Presets.Dark else Presets.Light
        // The Accent preset is the device's own Material You palette, built fresh here rather than
        // stored — the wallpaper (and so the palette) can change while the theme id stays the same,
        // so persisting the resolved colours would go stale. `Presets.Accent`'s own hardcoded
        // indigo survives as the fallback for pre-Android-12 and for OEM builds that don't publish
        // the palette resources.
        Presets.Accent.id -> systemDynamicTheme(
            context = context,
            forDarkTheme = systemDark,
            id = Presets.Accent.id,
            name = Presets.Accent.name,
        ) ?: Presets.Accent
        else -> stored
    }
    // Only Light/Dark/Auto/Accent — a custom theme's colors are exactly what the user picked in
    // the HSV editor, and overriding one of those 4 with the system accent would undo that choice
    // silently rather than respect it.
    val isCustomTheme = stored.id.startsWith(CustomThemePreferences.ID_PREFIX)
    if (useSystemAccent && !isCustomTheme) {
        // Which tone counts as "the accent" depends on the surface it lands on, so the *resolved*
        // theme's darkness decides — not the stored one, which is `Auto` (and always reports dark)
        // whenever the user is following the system. Getting this from `stored` would have pinned a
        // light-theme keyboard to the dark-theme accent.
        systemAccent(context, forDarkTheme = effective.isDark)?.let { accent ->
            effective = effective.copy(
                spacebarAccentColor = accent.accent,
                keyBackgroundPressed = accent.accent,
                // The matching on-accent tone, so labels on those keys stay readable against a
                // colour the theme's own keyTextColor was never chosen against. See
                // OmakeyTheme.labelOn.
                keyTextOnAccentColor = accent.onAccent,
            )
        }
    }
    return effective
}
