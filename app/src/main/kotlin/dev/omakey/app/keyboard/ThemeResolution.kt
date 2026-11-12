package dev.omakey.app.keyboard

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.omakey.core.theme.CustomThemePreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.SystemAccent
import dev.omakey.core.theme.systemAccent
import dev.omakey.core.theme.systemDynamicTheme

/** Turns whatever [ThemeRepository][dev.omakey.core.theme.ThemeRepository] has stored into what
 * should actually be rendered — the one place both the keyboard and `SettingsActivity` resolve
 * "Follow system" (a sentinel [Presets.Auto] entry, not a separate boolean layered on top of a
 * specific preset — matches how the OS's own light/dark setting works: one choice among Light/
 * Dark/System, not an independent toggle) and "pick accent color from system" (genuinely
 * orthogonal — you can want a fixed preset but still have the accent follow the device palette).
 * Composable so it can use [isSystemInDarkTheme], which already recomposes automatically on a
 * system light/dark change — no manual `ComponentCallbacks`/config-change listener needed.
 *
 * **The stored theme is never something to draw.** `Auto` carries Dark's colours and `Accent` a
 * fixed dark indigo, only as fallbacks. In the keyboard, the service resolves once and provides
 * the result as `LocalOmakeyTheme`; everything below reads that. `ExtensionPanelSlot` once
 * re-provided the stored theme underneath, which is why the emoji and clipboard panels stayed dark
 * on a light phone under Auto and Accent. `ThemeProvisionGuardTest` now holds that line. */
@Composable
fun resolveEffectiveTheme(stored: OmakeyTheme, useSystemAccent: Boolean): OmakeyTheme =
    resolveTheme(LocalContext.current, stored, useSystemAccent, systemDark = isSystemInDarkTheme())

/** [resolveEffectiveTheme] for callers outside composition (the service's inline-autofill request,
 * which can arrive before the keyboard has drawn), with the system's darkness passed in. */
fun resolveTheme(context: Context, stored: OmakeyTheme, useSystemAccent: Boolean, systemDark: Boolean): OmakeyTheme =
    resolveTheme(
        stored = stored,
        useSystemAccent = useSystemAccent,
        systemDark = systemDark,
        dynamicTheme = { dark ->
            systemDynamicTheme(context, forDarkTheme = dark, id = Presets.Accent.id, name = Presets.Accent.name)
        },
        systemAccent = { dark -> systemAccent(context, forDarkTheme = dark) },
    )

/** The decision itself, free of Android so it can be unit-tested. [dynamicTheme] and
 * [systemAccent] read the device palette for a dark or light surface, or return null where there
 * is none (pre-Android-12, some OEM builds). */
internal fun resolveTheme(
    stored: OmakeyTheme,
    useSystemAccent: Boolean,
    systemDark: Boolean,
    dynamicTheme: (forDarkTheme: Boolean) -> OmakeyTheme?,
    systemAccent: (forDarkTheme: Boolean) -> SystemAccent?,
): OmakeyTheme {
    var effective = when (stored.id) {
        Presets.Auto.id -> if (systemDark) Presets.Dark else Presets.Light
        // The Accent preset is the device's own Material You palette, built fresh here rather than
        // stored — the wallpaper (and so the palette) can change while the theme id stays the same,
        // so persisting the resolved colours would go stale. Without a device palette
        // (pre-Android-12, some OEM builds) it falls back to an indigo theme that still follows
        // light/dark — it used to stay dark on a light phone there.
        Presets.Accent.id -> dynamicTheme(systemDark) ?: Presets.accentFallback(systemDark)
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
        systemAccent(effective.isDark)?.let { accent ->
            effective = effective.copy(
                spacebarAccentColor = accent.accent,
                keyBackgroundPressed = accent.accent,
                // The matching on-accent tone, so labels on those keys stay readable against a
                // colour the theme's own keyTextColor was never chosen against. See
                // OmakeyTheme.labelOn.
                keyTextOnAccentColor = accent.onAccent,
                accentColor = accent.accent,
            )
        }
    }
    return effective
}
