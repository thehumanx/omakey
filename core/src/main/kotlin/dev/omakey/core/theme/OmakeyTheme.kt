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
    val keySpacingDp: Float = 4f,
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
)

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
        middleRowStripeColor = ColorSpec(0x14000000),
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
        middleRowStripeColor = ColorSpec(0x1FFFFFFF),
        gridBorderColor = ColorSpec(0xFFE0E0E0),
    )

    /** Not a real color scheme of its own — a sentinel selected via [id] that tells the render
     * layer (see `resolveEffectiveTheme` in the app module) to substitute [Light] or [Dark] based
     * on the system's current light/dark setting, live, without needing a restart. Its own color
     * fields are never actually shown (Dark's, copied here only as a harmless fallback in case
     * resolution is ever skipped) — resolution always replaces them before rendering. */
    val Auto = Dark.copy(id = "preset_auto", name = "Follow system")

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
        middleRowStripeColor = ColorSpec(0x1FFFFFFF),
        gridBorderColor = ColorSpec(0xFF6B70A8),
    )

    val all = listOf(Light, Dark, Auto, Accent)
}
