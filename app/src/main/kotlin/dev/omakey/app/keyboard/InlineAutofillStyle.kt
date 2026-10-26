package dev.omakey.app.keyboard

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.widget.inline.InlinePresentationSpec
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.omakey.app.R
import dev.omakey.app.keyboard.ui.INLINE_CHIP_GAP_DP
import dev.omakey.app.keyboard.ui.INLINE_CHIP_HEIGHT_DP
import dev.omakey.app.keyboard.ui.INLINE_CHIP_ROW_PADDING_DP
import dev.omakey.app.keyboard.ui.STRIP_BUTTON_WIDTH_DP
import dev.omakey.core.input.InlineAutofill
import dev.omakey.core.theme.OmakeyTheme

/**
 * The request omakey sends for a password manager's inline autofill chips: how big they may be and
 * how to draw them. The service draws the chips itself from this style bundle (the
 * `androidx.autofill` format every manager reads), so this is the only say omakey has in how they
 * look — chips filled like keys, labelled in the key label colour, so they sit in the strip like
 * omakey's own suggestions in every theme.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal object InlineAutofillStyle {

    /**
     * [stripWidth] is the keyboard's width in px and [buttons] how many buttons the strip has beside
     * the chips (1 or 2) — both as they are on this phone right now, since the strip's width depends
     * on the screen, the display scale and the keyboard mode. A chip may be as wide as the space
     * between those buttons, less room for the chip row's own "next chips" button — first real phone test: with
     * the whole screen width allowed, Bitwarden drew a login chip that ran under the language
     * button. Within that cap a chip still sizes to its own text, and the manager's chip layout
     * cuts a long account name short with "…".
     */
    fun request(context: Context, theme: OmakeyTheme, stripWidth: Int, buttons: Int): InlineSuggestionsRequest {
        val height = px(context, INLINE_CHIP_HEIGHT_DP)
        val maxWidth = InlineAutofill.maxChipWidth(
            stripWidth = stripWidth,
            reserved = px(context, buttons * STRIP_BUTTON_WIDTH_DP + 2 * INLINE_CHIP_ROW_PADDING_DP),
            // The chip row's "next chips" button, a strip button like the others.
            trailing = px(context, STRIP_BUTTON_WIDTH_DP),
            gap = px(context, INLINE_CHIP_GAP_DP),
            minWidth = height,
        )
        val spec = InlinePresentationSpec.Builder(Size(height, height), Size(maxWidth, height))
            .setStyle(styles(context, theme))
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    /** The size each chip is inflated at: its own width, the strip's chip height. */
    fun chipSize(context: Context): Size =
        Size(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, px(context, INLINE_CHIP_HEIGHT_DP))

    private fun styles(context: Context, theme: OmakeyTheme): Bundle {
        val fill = theme.keyBackground.argb.toInt()
        val label = theme.labelOn(theme.keyBackground).argb.toInt()
        // Subtitles (the site, a masked password) quieter than the account name, as omakey's own
        // secondary text is.
        val subtitle = (label and 0x00FFFFFF) or (0x99 shl 24)
        fun background() = Icon.createWithResource(context, R.drawable.inline_chip_background).setTint(fill)
        val side = px(context, 12)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(ViewStyle.Builder().setBackground(background()).setPadding(side, 0, side, 0).build())
            .setSingleIconChipStyle(ViewStyle.Builder().setBackground(background()).setPadding(0, 0, 0, 0).build())
            .setStartIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, px(context, 6), 0).build())
            .setTitleStyle(
                TextViewStyle.Builder().setTextColor(label).setTextSize(14f)
                    .setLayoutMargin(0, 0, px(context, 4), 0).build(),
            )
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(subtitle).setTextSize(12f).build())
            .build()
        return UiVersions.newStylesBuilder().addStyle(style).build()
    }

    private fun px(context: Context, dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
