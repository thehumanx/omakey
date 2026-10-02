package dev.omakey.core.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A whole custom-theme palette from one colour, the keyboard background (AGENTS.md §71).
 *
 * Picking six related colours by hand is hard to get right — a tap colour too close to the key to
 * see, an accent that vanishes against the keys. So the theme editor starts every colour from this
 * and lets the user override any of them on top; an untouched colour keeps following the background.
 *
 * Contrast is measured the WCAG way (relative luminance, sRGB-linearised), and the floors are the
 * WCAG ones: [MIN_TEXT_CONTRAST] (4.5:1) for key labels, [MIN_UI_CONTRAST] (3:1) for the accent
 * and the grid border, which are non-text UI.
 */
object ThemeDerivation {

    const val MIN_TEXT_CONTRAST = 4.5
    const val MIN_UI_CONTRAST = 3.0

    data class Palette(
        val isDark: Boolean,
        val key: ColorSpec,
        val keyTap: ColorSpec,
        val homeRowStripe: ColorSpec,
        val spacebar: ColorSpec,
        val accent: ColorSpec,
        val gridBorder: ColorSpec,
    )

    private val WHITE = ColorSpec(0xFFFFFFFF)
    private val BLACK = ColorSpec(0xFF000000)

    /** Light text and dark text, as the presets use them; [textOn] picks between them. */
    val LIGHT_TEXT = ColorSpec(0xFFF2F2F2)
    val DARK_TEXT = ColorSpec(0xFF1A1A1A)

    /** Whether [background] reads as dark: white text contrasts with it better than black does. */
    fun isDark(background: ColorSpec): Boolean = contrast(background, WHITE) >= contrast(background, BLACK)

    /** The palette for [background]. With [keyOverride] (the user picked a key colour), that is
     * the key, used as is — the tap colour, spacebar and accent then follow it rather than the key
     * the background would have suggested. */
    fun fromBackground(background: ColorSpec, keyOverride: ColorSpec? = null): Palette {
        val dark = isDark(background)
        // Keys sit slightly off the background: lighter on a dark keyboard, near-white on a light
        // one — and darker instead when the background is already about as light as it gets.
        var key = keyOverride ?: if (dark) mix(background, WHITE, 0.07) else mix(background, WHITE, 0.75)
        if (keyOverride == null) {
            if (!dark && contrast(key, background) < 1.04) key = mix(background, BLACK, 0.06)
            // Mid-tones are where neither light nor dark text reaches 4.5:1; move the key away from
            // whichever text it gets until it does.
            val text = textOn(key)
            key = untilContrast(key, text, MIN_TEXT_CONTRAST, towardsLight = text == DARK_TEXT)
        }
        val keyDark = textOn(key) == LIGHT_TEXT
        val keyTap = if (keyDark) mix(key, WHITE, 0.18) else mix(key, BLACK, 0.16)
        return Palette(
            isDark = dark,
            key = key,
            keyTap = keyTap,
            // A translucent tint, like the presets', so it reads on whatever key colour is under it.
            homeRowStripe = if (dark) ColorSpec(0x1FFFFFFF) else ColorSpec(0x14000000),
            // Neutral like every preset's spacebar; colour is the user's to add.
            spacebar = key,
            accent = accentFor(background, key, keyDark),
            gridBorder = untilContrast(if (dark) mix(background, WHITE, 0.75) else mix(background, BLACK, 0.75), background, MIN_UI_CONTRAST, towardsLight = dark),
        )
    }

    /** Light or dark label text, whichever contrasts more with [surface]. */
    fun textOn(surface: ColorSpec): ColorSpec =
        if (contrast(surface, LIGHT_TEXT) >= contrast(surface, DARK_TEXT)) LIGHT_TEXT else DARK_TEXT

    /** The background's own hue when it has one, a calm blue when it's grey; then lightened (dark
     * themes) or darkened (light ones) until it stands out from the keys at 3:1. */
    private fun accentFor(background: ColorSpec, key: ColorSpec, dark: Boolean): ColorSpec {
        val (h, s, _) = hsl(background)
        val hue = if (s >= 0.15) h else 211.0
        val start = fromHsl(hue, 0.65, if (dark) 0.6 else 0.45)
        return untilContrast(start, key, MIN_UI_CONTRAST, towardsLight = dark)
    }

    private fun untilContrast(color: ColorSpec, against: ColorSpec, floor: Double, towardsLight: Boolean): ColorSpec {
        var c = color
        repeat(40) {
            if (contrast(c, against) >= floor) return c
            c = mix(c, if (towardsLight) WHITE else BLACK, 0.1)
        }
        return c
    }

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: ColorSpec, b: ColorSpec): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun relativeLuminance(c: ColorSpec): Double {
        fun channel(v: Long): Double {
            val s = (v and 0xFF) / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.argb shr 16) + 0.7152 * channel(c.argb shr 8) + 0.0722 * channel(c.argb)
    }

    /** [a] moved [t] of the way to [b], opaque. */
    fun mix(a: ColorSpec, b: ColorSpec, t: Double): ColorSpec {
        fun ch(c: ColorSpec, shift: Int) = ((c.argb shr shift) and 0xFF).toDouble()
        fun lerp(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toLong().coerceIn(0, 255)
        return ColorSpec(0xFF000000L or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0))
    }

    private fun hsl(c: ColorSpec): Triple<Double, Double, Double> {
        val r = ((c.argb shr 16) and 0xFF) / 255.0
        val g = ((c.argb shr 8) and 0xFF) / 255.0
        val b = (c.argb and 0xFF) / 255.0
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2
        val d = maxC - minC
        if (d == 0.0) return Triple(0.0, 0.0, l)
        val s = d / (1 - abs(2 * l - 1))
        val h = when (maxC) {
            r -> 60 * (((g - b) / d).mod(6.0))
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): ColorSpec {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60).mod(2.0) - 1))
        val m = l - c / 2
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun to8(v: Double) = ((v + m) * 255).toLong().coerceIn(0, 255)
        return ColorSpec(0xFF000000L or (to8(r) shl 16) or (to8(g) shl 8) or to8(b))
    }
}
