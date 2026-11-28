package dev.omakey.app.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.R

/*
 * omakey's design tokens, mirroring the design file's variables (Figma "omakey", Foundations page):
 * the "omakey" colour collection (Light/Dark), the "dimensions" collection, and the text styles.
 * Settings and the app's own screens use only these. The keyboard itself keeps following the
 * user's keyboard theme.
 */

/** The colour roles. One accent, for the single selected or active thing on a screen. */
@Immutable
data class OmakeyColors(
    val ground: Color,
    val surface: Color,
    val tonal: Color,
    val ink: Color,
    val onInk: Color,
    val muted: Color,
    val subtle: Color,
    val hairline: Color,
    /** A thumb or chip that sits one step above [tonal]: white in light, lighter grey in dark
     * (where [surface] is darker than the tonal track, so it can't step up). */
    val raised: Color,
    val accent: Color,
    val danger: Color,
    val isDark: Boolean,
) {
    companion object {
        val Light = OmakeyColors(
            ground = Color(0xFFF3F3F2),
            surface = Color(0xFFFFFFFF),
            tonal = Color(0xFFECECEA),
            ink = Color(0xFF161616),
            onInk = Color(0xFFFFFFFF),
            muted = Color(0xFF5E5E5E),
            subtle = Color(0xFF8A8A8A),
            hairline = Color(0xFFE4E4E2),
            raised = Color(0xFFFFFFFF),
            accent = Color(0xFF2F5FF5),
            danger = Color(0xFFB3261E),
            isDark = false,
        )
        val Dark = OmakeyColors(
            ground = Color(0xFF0E0E0E),
            surface = Color(0xFF1A1A1A),
            tonal = Color(0xFF262626),
            ink = Color(0xFFF2F2F2),
            onInk = Color(0xFF161616),
            muted = Color(0xFFA3A3A3),
            subtle = Color(0xFF6B6B6B),
            hairline = Color(0xFF2E2E2E),
            raised = Color(0xFF3A3A3A),
            accent = Color(0xFF7C9CFF),
            danger = Color(0xFFF2B8B5),
            isDark = true,
        )
    }
}

/** The 4 dp spacing scale. Screen gutter is [s16]; touch targets are at least 48. */
object Space {
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
    val s32 = 32.dp
    val s48 = 48.dp
}

/** Three radii only: keys and chips, controls (buttons, tiles, fields), cards and sheets. */
object Radius {
    val key = 10.dp
    val control = 14.dp
    val card = 24.dp
}

/** Figtree at the four weights the type scale uses. It ships as one variable font, and tagging a
 * weight alone doesn't move its wght axis on every Android version, so each weight pins the axis. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Figtree: FontFamily = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            resId = R.font.figtree_variable,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

/** The type scale. No other sizes are used in Settings. */
@Immutable
data class OmakeyType(
    val headline: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
    val title: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    val row: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    val body: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    val label: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    val caption: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    val button: TextStyle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
)

val LocalOmakeyColors = staticCompositionLocalOf { OmakeyColors.Light }
val LocalOmakeyType = staticCompositionLocalOf { OmakeyType() }

/** Shorthand: `Omakey.colors.ink`, `Omakey.type.row`. */
object Omakey {
    val colors: OmakeyColors
        @Composable @ReadOnlyComposable get() = LocalOmakeyColors.current
    val type: OmakeyType
        @Composable @ReadOnlyComposable get() = LocalOmakeyType.current
}

/**
 * The app's theme: the tokens above, plus a Material scheme built only from them, so any Material
 * widget still in use (sliders, text selection, the odd menu) takes the same neutral colours
 * instead of Material's stock purple, and Figtree everywhere.
 */
@Composable
fun OmakeyDesignTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) OmakeyColors.Dark else OmakeyColors.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.ink, onPrimary = c.onInk, primaryContainer = c.tonal, onPrimaryContainer = c.ink,
            inversePrimary = c.muted, secondary = c.muted, onSecondary = c.onInk, secondaryContainer = c.tonal,
            onSecondaryContainer = c.ink, tertiary = c.accent, onTertiary = c.onInk, tertiaryContainer = c.tonal,
            onTertiaryContainer = c.ink, background = c.ground, onBackground = c.ink, surface = c.ground,
            onSurface = c.ink, surfaceVariant = c.surface, onSurfaceVariant = c.muted, surfaceTint = Color.Transparent,
            inverseSurface = c.ink, inverseOnSurface = c.onInk, error = c.danger, outline = c.subtle,
            outlineVariant = c.hairline, scrim = Color.Black, surfaceBright = c.tonal, surfaceDim = c.ground,
            surfaceContainerLowest = c.ground, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerHighest = c.tonal,
        )
    } else {
        lightColorScheme(
            primary = c.ink, onPrimary = c.onInk, primaryContainer = c.tonal, onPrimaryContainer = c.ink,
            inversePrimary = c.subtle, secondary = c.muted, onSecondary = c.onInk, secondaryContainer = c.tonal,
            onSecondaryContainer = c.ink, tertiary = c.accent, onTertiary = c.onInk, tertiaryContainer = c.tonal,
            onTertiaryContainer = c.ink, background = c.ground, onBackground = c.ink, surface = c.ground,
            onSurface = c.ink, surfaceVariant = c.surface, onSurfaceVariant = c.muted, surfaceTint = Color.Transparent,
            inverseSurface = c.ink, inverseOnSurface = c.onInk, error = c.danger, outline = c.subtle,
            outlineVariant = c.hairline, scrim = Color.Black, surfaceBright = c.surface, surfaceDim = c.tonal,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surface, surfaceContainerHighest = c.tonal,
        )
    }
    val t = OmakeyType()
    val typography = Typography().let { base ->
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = Figtree),
            displayMedium = base.displayMedium.copy(fontFamily = Figtree),
            displaySmall = base.displaySmall.copy(fontFamily = Figtree),
            headlineLarge = t.headline,
            headlineMedium = t.headline,
            headlineSmall = t.title,
            titleLarge = t.title,
            titleMedium = t.row,
            titleSmall = t.label,
            bodyLarge = t.row.copy(fontWeight = FontWeight.Normal),
            bodyMedium = t.body,
            bodySmall = t.caption,
            labelLarge = t.button.copy(fontSize = 14.sp),
            labelMedium = t.label,
            labelSmall = t.caption,
        )
    }
    CompositionLocalProvider(LocalOmakeyColors provides c, LocalOmakeyType provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
