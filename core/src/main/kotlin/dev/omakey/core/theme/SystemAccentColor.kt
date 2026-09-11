package dev.omakey.core.theme

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The device's Material You accent, read from the actual system palette resources rather than
 * approximated from wallpaper colours — so it genuinely matches what the user already sees as "the
 * accent colour" everywhere else in the OS.
 *
 * ## Why the tone depends on the theme
 *
 * Android 12+ publishes each of its five palettes at thirteen tones (`system_accent1_0` through
 * `system_accent1_1000`). **Which tone is "the accent" is not fixed — it depends on whether it is
 * being shown on a light or a dark surface.** The platform's own mapping for `colorPrimary` is
 * `system_accent1_600` on light and `system_accent1_200` on dark, with `colorOnPrimary` taking
 * `system_accent1_0` and `system_accent1_800` respectively.
 *
 * This used to read `system_accent1_500` unconditionally, for both light and dark themes. That is
 * the mid tone, which is neither of the two the platform uses, so the keyboard's accent never
 * actually matched the system accent the setting promises — and being a single fixed value, it was
 * too dark to read against a dark theme and too light against a light one. Reading a hardcoded
 * shade out of a palette built specifically to be tone-aware is the bug.
 *
 * ## Why both halves are returned
 *
 * An accent is not one colour, it is a pair. omakey uses it in two opposite roles: as a *background*
 * (a pressed key, the caps-lock key, the spacebar) with key labels drawn on top, and as a
 * *foreground* (the active-shift icon tint) drawn on the key surface. A single colour cannot serve
 * both — whichever tone reads well behind text is exactly the tone that disappears when used as
 * text. [SystemAccent.onAccent] is what goes on top of [SystemAccent.accent], the same
 * primary/onPrimary pairing Material itself uses.
 *
 * Returns null pre-Android-12, or if the resources can't be resolved for any reason (some OEM skins
 * omit or override these ids) — callers fall back to the theme's own colours.
 */
data class SystemAccent(val accent: ColorSpec, val onAccent: ColorSpec)

fun systemAccent(context: Context, forDarkTheme: Boolean): SystemAccent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val accentRes = if (forDarkTheme) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
    val onAccentRes = if (forDarkTheme) android.R.color.system_accent1_800 else android.R.color.system_accent1_0
    return runCatching {
        SystemAccent(
            accent = ColorSpec.fromArgbInt(ContextCompat.getColor(context, accentRes)),
            onAccent = ColorSpec.fromArgbInt(ContextCompat.getColor(context, onAccentRes)),
        )
    }.getOrNull()
}

/**
 * A whole keyboard palette derived from the device's Material You colours, for the **Accent**
 * preset.
 *
 * That preset used to be a fixed indigo palette that had nothing to do with the device — reported,
 * reasonably, as "where is this bluish purple coming from?" Its name promised something it never
 * did. It now means what it says: surfaces come from the wallpaper-derived neutral ramps, and the
 * highlight from the accent ramp.
 *
 * **Follows the system's own light/dark setting**, via [forDarkTheme], rather than being pinned
 * dark. A Material You theme that stays dark on a light-themed phone isn't Material You. This is
 * the one preset other than `Auto` that tracks the system that way, and unlike `Auto` it also
 * tracks the *colour*.
 *
 * Tones are the ones the platform itself maps these roles to — see [systemAccent] for the
 * accent/on-accent pair and why the tone depends on the surface. The neutral choices below follow
 * the same logic: higher tone numbers are darker, so a dark theme takes its surfaces from the
 * bottom of the ramp and a light theme from the top.
 *
 * Returns null pre-Android-12, or if any resource can't be resolved — callers fall back to the
 * hardcoded indigo, which is still a perfectly good theme, just not a dynamic one.
 */
fun systemDynamicTheme(context: Context, forDarkTheme: Boolean, id: String, name: String): OmakeyTheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val accent = systemAccent(context, forDarkTheme) ?: return null
    return runCatching {
        fun color(resId: Int) = ColorSpec.fromArgbInt(ContextCompat.getColor(context, resId))
        if (forDarkTheme) {
            OmakeyTheme(
                id = id,
                name = name,
                isDark = true,
                suggestionBarBackground = color(android.R.color.system_neutral1_900),
                keyboardBackground = color(android.R.color.system_neutral1_800),
                keyBackground = color(android.R.color.system_neutral2_700),
                keySpecialBackground = color(android.R.color.system_neutral2_800),
                keyTextColor = color(android.R.color.system_neutral1_50),
                // The accent is the one saturated thing on the keyboard, and it is a *background*
                // with labels on it — so it takes the pale tone plus the matching dark on-accent,
                // exactly as the platform pairs colorPrimary with colorOnPrimary.
                keyBackgroundPressed = accent.accent,
                keyTextOnAccentColor = accent.onAccent,
                // Neutral by default, like every other preset: the spacebar only picks up the
                // accent if the user turns on "pick accent colour from system".
                spacebarAccentColor = color(android.R.color.system_neutral2_700),
                middleRowStripeColor = ColorSpec(0x1FFFFFFF),
                gridBorderColor = color(android.R.color.system_neutral2_400),
            )
        } else {
            OmakeyTheme(
                id = id,
                name = name,
                isDark = false,
                suggestionBarBackground = color(android.R.color.system_neutral1_50),
                keyboardBackground = color(android.R.color.system_neutral1_100),
                keyBackground = color(android.R.color.system_neutral1_0),
                keySpecialBackground = color(android.R.color.system_neutral2_100),
                keyTextColor = color(android.R.color.system_neutral1_900),
                keyBackgroundPressed = accent.accent,
                keyTextOnAccentColor = accent.onAccent,
                spacebarAccentColor = color(android.R.color.system_neutral1_0),
                middleRowStripeColor = ColorSpec(0x14000000),
                gridBorderColor = color(android.R.color.system_neutral2_600),
            )
        }
    }.getOrNull()
}
