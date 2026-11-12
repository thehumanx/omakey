package dev.omakey.core.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class KeyShape { ROUNDED, SQUARE, PILL }

/** Grid mode's border stroke width, user-editable as a 3-step preset (not a continuous slider —
 * matches the rest of the theme editor's simple-choice controls) rather than a raw dp value.
 * Actual dp mapping lives in the render layer (`GridBorderWidth.toDp()` in `KeyboardRoot.kt`),
 * not here — this module has no reason to depend on Compose's `Dp` type for what's otherwise a
 * plain enum. */
enum class GridBorderWidth { SM, MD, LG }

@Serializable
data class ColorSpec(val argb: Long) {

    /** Perceived brightness, 0 (black) to 1 (white) — the same luma weighting the theme editor
     * already used to decide light-on-dark. Alpha is ignored: these are opaque surface colours. */
    val luminance: Float
        get() {
            val red = ((argb shr 16) and 0xFF) / 255f
            val green = ((argb shr 8) and 0xFF) / 255f
            val blue = (argb and 0xFF) / 255f
            return 0.299f * red + 0.587f * green + 0.114f * blue
        }

    companion object {
        fun fromArgbInt(argbInt: Int) = ColorSpec(argbInt.toLong() and 0xFFFFFFFFL)
    }
}

@Serializable
data class FontSpec(val familyId: String = "system_default")

@Serializable
data class OmakeyTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val keyboardBackground: ColorSpec,
    val keyBackground: ColorSpec,
    val keyBackgroundPressed: ColorSpec,
    val keyTextColor: ColorSpec,
    val keySpecialBackground: ColorSpec,
    val suggestionBarBackground: ColorSpec,
    val keyShape: KeyShape = KeyShape.ROUNDED,
    val fontFamily: FontSpec = FontSpec(),
    // Default values below so a theme JSON already persisted on a user's device (missing these
    // newer fields) still deserializes fine via kotlinx.serialization's optional-field handling.
    val spacebarAccentColor: ColorSpec = ColorSpec(0xFF4A90D9),
    val middleRowStripeColor: ColorSpec = ColorSpec(0x1FFFFFFF),
    // User-editable via the theme editor's 5th carousel page — the single color every Grid-mode
    // border (main key grid, suggestion/tools/numbers strip, extension-panel header tabs) draws
    // with, everywhere, at full opacity, no per-key-type dimming. Replaces an earlier design that
    // derived two different alpha levels from keyTextColor (regular vs. "special" keys like
    // shift/backspace) — real user feedback: that dimming read as an inconsistency/bug ("border
    // color affected"), not an intentional visual cue. Defaults to a sensible isDark-derived value
    // (dark grey on light themes, light grey on dark themes) so a theme JSON already persisted on
    // a user's device before this field existed still gets a reasonable border color rather than
    // deserializing to some arbitrary flat default.
    val gridBorderColor: ColorSpec = if (isDark) ColorSpec(0xFFE0E0E0) else ColorSpec(0xFF2A2A2A),
    val gridBorderWidth: GridBorderWidth = GridBorderWidth.MD,
    // Custom themes only (see Presets, none of which set this) — which layout mode this theme
    // was created/last edited for, so the Settings custom-theme list can show only the ones
    // relevant to whichever mode is currently active. A theme built while previewing Normal mode
    // never had its Grid-specific fields (gridBorderColor/gridBorderWidth above) intentionally
    // chosen, and vice versa — real user feedback: a custom theme "doesn't always work" for the
    // other mode, not because it's broken, but because half its fields were never actually
    // looked at while making it. Null (the default, and what every custom theme saved before this
    // field existed deserializes to) means "not tagged" — shown regardless of active mode, rather
    // than silently disappearing from one mode's list.
    val designedForLayoutMode: LayoutMode? = null,
    /**
     * Label/icon colour for keys whose background is [spacebarAccentColor] or
     * [keyBackgroundPressed] — the spacebar, a pressed cell, the caps-lock key.
     *
     * Null (the default, and what every theme persisted before this field existed deserializes to)
     * means "work it out from the background", which [labelOn] does. Every preset leaves it null:
     * their accent colours were chosen alongside [keyTextColor] and already contrast with it.
     *
     * It exists for the "pick accent colour from system" path, which drops an arbitrary colour into
     * a theme whose [keyTextColor] was chosen without knowing about it. Android publishes the
     * matching on-accent tone (see [systemAccent]), and using the OS's own answer is better than
     * inferring one.
     */
    val keyTextOnAccentColor: ColorSpec? = null,
    /**
     * The colour of "on": caps lock, active shift, an open quick-access or language button, the
     * selected tile in a panel, the resize handles. Null (every theme saved before this existed)
     * means [keyTextColor] — see [accent].
     *
     * It exists because these states used to borrow [keyBackgroundPressed], which is a *press*
     * colour: on Light it is a pale grey, so active shift was a grey arrow on a white key, and caps
     * lock drew that same grey icon on that same grey fill — invisible. Reported as "the capslock
     * state is not right" and "everything is grey/white whatever the theme".
     */
    val accentColor: ColorSpec? = null,
) {
    /** The theme's accent — see [accentColor]. Falls back to [keyTextColor], which always contrasts
     * with the keys (that is its job), so Light and Dark get a crisp black/white "on" state instead
     * of a colour they never chose. */
    val accent: ColorSpec get() = accentColor ?: keyTextColor

    /** What to draw on top of [accent]: the palette's own on-accent tone, else the key colour,
     * else [ThemeDerivation.readableOn] — the first that reaches WCAG's 4.5:1. A supplied tone is
     * not trusted blindly: a user's custom accent can land where it does not work. */
    val onAccent: ColorSpec
        get() {
            keyTextOnAccentColor?.takeIf { ThemeDerivation.contrast(it, accent) >= ThemeDerivation.MIN_TEXT_CONTRAST }?.let { return it }
            if (ThemeDerivation.contrast(keyBackground, accent) >= ThemeDerivation.MIN_TEXT_CONTRAST) return keyBackground
            return ThemeDerivation.readableOn(accent)
        }

    /**
     * The colour to draw a label in on top of [background], at WCAG's 4.5:1 for text.
     *
     * [keyTextColor] whenever it reaches that, because a theme's own colours are chosen to go
     * together. Otherwise — a system accent injected by `resolveEffectiveTheme`, or a custom theme
     * whose key and background the user set to opposite brightnesses — [keyTextOnAccentColor] if
     * the palette supplied one that passes, else [ThemeDerivation.readableOn].
     *
     * A readability floor, not a restyling: for every preset this returns [keyTextColor] on every
     * surface (`WcagContrastTest` pins that). Every label and every piece of text on a keyboard
     * surface goes through here rather than using [keyTextColor] directly.
     */
    fun labelOn(background: ColorSpec): ColorSpec {
        if (ThemeDerivation.contrast(keyTextColor, background) >= ThemeDerivation.MIN_TEXT_CONTRAST) return keyTextColor
        keyTextOnAccentColor
            ?.takeIf { ThemeDerivation.contrast(it, background) >= ThemeDerivation.MIN_TEXT_CONTRAST }
            ?.let { return it }
        return ThemeDerivation.readableOn(background)
    }

    /**
     * [labelOn] dimmed toward [background] as if drawn at [alpha], but never below [floor]
     * (WCAG 4.5:1 for text, 3:1 for a decoration). Returns an opaque colour.
     *
     * Fixed alphas were the bug: 45% "faded" suggestions came out at about 2.9:1 on Light, and
     * the spacebar's language name was fainter still. Fading is a hint that something is
     * secondary, which it can still be at 4.5:1 on a dark theme, where there is room to fade.
     */
    fun fadedLabelOn(background: ColorSpec, alpha: Float, floor: Double = ThemeDerivation.MIN_TEXT_CONTRAST): ColorSpec {
        val label = labelOn(background)
        val wanted = (1.0 - alpha).coerceIn(0.0, 1.0)
        fun at(t: Double) = ThemeDerivation.mix(label, background, t)
        if (ThemeDerivation.contrast(at(wanted), background) >= floor) return at(wanted)
        // Contrast falls steadily as the label moves toward the background: find the most fade
        // that still clears the floor.
        var lo = 0.0
        var hi = wanted
        repeat(24) {
            val mid = (lo + hi) / 2
            if (ThemeDerivation.contrast(at(mid), background) >= floor) lo = mid else hi = mid
        }
        return at(lo)
    }

    /** What the suggestion strip, and a panel shown in its place (emoji, clipboard), is filled
     * with: the keyboard background in Grid mode, where every cell shares it, and the suggestion
     * bar's own colour otherwise. Text on those goes through [labelOn] against this. */
    fun stripSurface(isGridMode: Boolean): ColorSpec = if (isGridMode) keyboardBackground else suggestionBarBackground

    /**
     * [accent] as a *foreground* on [surface] (the active-shift icon, a selected-tab tint, the
     * resize handles), lifted or deepened in its own hue until it reaches WCAG's 3:1 for non-text
     * UI. Unchanged whenever it already does, which is every preset and every derived theme.
     */
    fun accentOn(surface: ColorSpec): ColorSpec =
        ThemeDerivation.towardContrast(accent, surface, ThemeDerivation.MIN_UI_CONTRAST)
}

object ThemeSerializer {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun toJson(theme: OmakeyTheme): String = json.encodeToString(OmakeyTheme.serializer(), theme)

    fun fromJson(jsonString: String): OmakeyTheme = json.decodeFromString(OmakeyTheme.serializer(), jsonString)
}

/** Preset themes shipped at launch. */
object Presets {
    val Light = OmakeyTheme(
        id = "preset_light",
        name = "Light",
        isDark = false,
        keyboardBackground = ColorSpec(0xFFF2F2F2),
        keyBackground = ColorSpec(0xFFFFFFFF),
        keyBackgroundPressed = ColorSpec(0xFFD0D0D0),
        keyTextColor = ColorSpec(0xFF1A1A1A),
        keySpecialBackground = ColorSpec(0xFFE0E0E0),
        suggestionBarBackground = ColorSpec(0xFFFAFAFA),
        // Neutral (same as keyBackground) by default — the spacebar blends in with the rest of
        // the row unless the user explicitly opts into "Pick accent color from system", which is
        // the only thing that colors it (see resolveEffectiveTheme). It used to be a fixed blue
        // shown unconditionally, which looked like an accent color nobody asked for.
        spacebarAccentColor = ColorSpec(0xFFFFFFFF),
        middleRowStripeColor = ThemeDerivation.homeRowStripe(isDark = false),
        gridBorderColor = ColorSpec(0xFF2A2A2A),
    )

    val Dark = OmakeyTheme(
        id = "preset_dark",
        name = "Dark",
        isDark = true,
        keyboardBackground = ColorSpec(0xFF1E1E1E),
        keyBackground = ColorSpec(0xFF2C2C2C),
        keyBackgroundPressed = ColorSpec(0xFF454545),
        keyTextColor = ColorSpec(0xFFF2F2F2),
        keySpecialBackground = ColorSpec(0xFF3A3A3A),
        suggestionBarBackground = ColorSpec(0xFF161616),
        // Neutral (same as keyBackground) — see Light's identical comment above.
        spacebarAccentColor = ColorSpec(0xFF2C2C2C),
        middleRowStripeColor = ThemeDerivation.homeRowStripe(isDark = true),
        gridBorderColor = ColorSpec(0xFFE0E0E0),
    )

    /** Not a real color scheme of its own — a sentinel selected via [id] that tells the render
     * layer (see `resolveEffectiveTheme` in the app module) to substitute [Light] or [Dark] based
     * on the system's current light/dark setting, live, without needing a restart. Its own color
     * fields are never actually shown (Dark's, copied here only as a harmless fallback in case
     * resolution is ever skipped) — resolution always replaces them before rendering. */
    val Auto = Dark.copy(id = "preset_auto", name = "Follow system")

    /**
     * **Fallback palette only.** At runtime `resolveEffectiveTheme` replaces this entire theme with
     * one built from the device's Material You colours (`systemDynamicTheme`) — surfaces from the
     * neutral ramps, highlight from the accent ramp, following the system's own light/dark setting.
     *
     * These hex values are what a device without that palette gets: pre-Android-12, or an OEM build
     * that doesn't publish the `system_accent*`/`system_neutral*` resources. They are also what the
     * theme *used* to be unconditionally, which was the bug — a preset named "Accent" that had
     * nothing to do with the device's accent, reported as "where is this bluish purple coming
     * from?". Kept rather than deleted because it is still a decent palette when there is nothing
     * to derive one from, and because something has to exist here for the id to refer to.
     */
    val Accent = OmakeyTheme(
        id = "preset_accent",
        name = "Accent",
        isDark = true,
        keyboardBackground = ColorSpec(0xFF12131A),
        keyBackground = ColorSpec(0xFF1F2233),
        keyBackgroundPressed = ColorSpec(0xFF3D4EFF),
        keyTextColor = ColorSpec(0xFFE8E8FF),
        keySpecialBackground = ColorSpec(0xFF2A2D45),
        suggestionBarBackground = ColorSpec(0xFF0C0D12),
        keyShape = KeyShape.PILL,
        spacebarAccentColor = ColorSpec(0xFF3D4EFF),
        middleRowStripeColor = ThemeDerivation.homeRowStripe(isDark = true),
        gridBorderColor = ColorSpec(0xFF6B70A8),
        // The indigo lifted until it reaches WCAG's 3:1 against these keys. As a plain 0xFF3D4EFF
        // it was 2.8:1, so caps lock and active shift read weakly on the very preset named for
        // its accent; the old luma-gap check passed it. Pressed keys and the spacebar keep the
        // deeper tone, where the labels drawn on them reach 4.5:1.
        accentColor = ThemeDerivation.towardContrast(ColorSpec(0xFF3D4EFF), ColorSpec(0xFF1F2233), ThemeDerivation.MIN_UI_CONTRAST),
    )

    /**
     * What the Accent preset shows where the device publishes no Material You palette
     * (pre-Android-12, some OEM builds), following the system's light/dark like the real thing.
     *
     * Dark is [Accent]'s own palette. Light used to not exist: those phones got the dark indigo
     * keyboard on a light system. It is derived from [Accent]'s indigo the same way a custom theme
     * is derived from its background, so its contrast is guaranteed by the same rules rather than
     * by hand-picked hex values.
     */
    fun accentFallback(dark: Boolean): OmakeyTheme {
        if (dark) return Accent
        val indigo = Accent.accent
        val background = ThemeDerivation.mix(indigo, ColorSpec(0xFFFFFFFF), 0.92)
        val palette = ThemeDerivation.fromBackground(background)
        val accent = ThemeDerivation.towardContrast(indigo, palette.key, ThemeDerivation.MIN_UI_CONTRAST)
        return ThemeDerivation.buildTheme(
            id = Accent.id,
            name = Accent.name,
            background = background,
            palette = palette.copy(accent = accent),
        ).copy(keyShape = Accent.keyShape)
    }

    val all = listOf(Light, Dark, Auto, Accent)
}
