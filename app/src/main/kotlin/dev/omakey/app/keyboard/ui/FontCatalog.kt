package dev.omakey.app.keyboard.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.omakey.app.R
import dev.omakey.core.theme.FontChoices

/** Maps a persisted font id to the actual FontFamily. Lives in the app module (not core) since it
 * needs R.font resource ids. Unknown/system-default ids resolve to null, meaning "use whatever
 * the Text composable would use by default." */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object FontCatalog {
    // Poppins ships as discrete static-weight files, not a variable font — the Medium *file*'s
    // glyphs are physically drawn at 500 regardless of what FontWeight tag is attached, so
    // dialing the weight down requires the actual Medium file, not just a different tag.
    private val PoppinsMedium = FontFamily(Font(R.font.poppins_medium, FontWeight.Medium))

    // Figtree is a true variable font (wght axis), but passing FontWeight alone to Font(resId,
    // weight) only tags the FontFamily for style matching — it does NOT reliably drive the
    // font's own wght axis on every API level, which is why it kept rendering at the file's
    // default (thin) weight regardless of which FontWeight was requested. Pinning the axis
    // explicitly via variationSettings forces the actual glyph outlines to interpolate to 600.
    private val FigtreeSemiBold = FontFamily(
        Font(
            resId = R.font.figtree_variable,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
    )

    fun resolve(fontId: String): FontFamily? = when (fontId) {
        FontChoices.POPPINS_BOLD -> PoppinsMedium
        FontChoices.FIGTREE_BOLD -> FigtreeSemiBold
        else -> null
    }

    val displayNames = mapOf(
        FontChoices.SYSTEM_DEFAULT to "System default",
        FontChoices.POPPINS_BOLD to "Poppins",
        FontChoices.FIGTREE_BOLD to "Figtree",
    )
}
