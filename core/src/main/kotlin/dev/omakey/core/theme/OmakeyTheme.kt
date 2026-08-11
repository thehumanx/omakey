package dev.omakey.core.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class KeyShape { ROUNDED, SQUARE, PILL }

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
        spacebarAccentColor = ColorSpec(0xFFCFE3FA),
        middleRowStripeColor = ColorSpec(0x14000000),
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
        spacebarAccentColor = ColorSpec(0xFF3D6FA8),
        middleRowStripeColor = ColorSpec(0x1FFFFFFF),
    )

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
    )

    val all = listOf(Light, Dark, Accent)
}
