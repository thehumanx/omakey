package dev.omakey.app.keyboard

import android.view.inputmethod.EditorInfo
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.feedback.HapticSoundSettings
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.theme.FontChoices
import dev.omakey.core.theme.LayoutMode
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets

/*
 * What the keyboard is currently showing, split out of KeyboardViewModel.kt — which was 1600 lines
 * and where this sat as preamble before any behaviour started. Pure data with no behaviour of its
 * own, so the move carries no risk and takes the state description out of the file that changes
 * most.
 */

/** What the strip above the key grid is currently showing. Fleksy-style: one shared strip slot,
 * not three permanently-visible rows — suggestions is the default/most-used tab, the other two
 * are a tap away. */
enum class TopStripTab { SUGGESTIONS, TOOLS, NUMBERS }

/** Whether `suggestions[0]` is an [AutocorrectIndex.alternatives] result (a fix/variant of a
 * specific word — quoted in the strip) or an ordinary next-word prediction (unquoted). Purely a
 * rendering hint; *how* accepting a suggestion is applied is governed by
 * [KeyboardViewModel.ActiveCorrection], not this. */
enum class SuggestionKind { PLAIN, CORRECTION }

data class KeyboardUiState(
    val layout: KeyboardLayout = KeyboardLocale.Default.letterLayout,
    /** Row count of the active language's letter layout — the unit row height is divided by, so
     * keys keep one size across letters, symbols and shift layers. Per language because not every
     * alphabet fits in four rows. */
    val baseRowCount: Int = KeyboardLocale.Default.letterLayout.rows.size,
    val shiftOn: Boolean = false,
    /** True once shift has been long-pressed into caps-lock — every letter is capitalized until
     * shift is tapped again, unlike plain [shiftOn] which is a one-shot "capitalize just the next
     * letter" that clears itself after a single character (see [commitTypedText]). */
    val capsLockOn: Boolean = false,
    val suggestions: List<String> = emptyList(),
    /** Emoji matching the word currently being typed/just finished (see
     * [dev.omakey.core.emoji.WordEmojiSuggestions]), rendered as extra chips alongside
     * [suggestions] — an entirely separate, independent row: tapping one inserts the emoji next
     * to the word rather than replacing/cycling it, so it never interacts with [firstSuggestionKind]
     * / [activeSuggestionIndex] / correction-cycling state at all. */
    val emojiSuggestions: List<String> = emptyList(),
    val theme: OmakeyTheme = Presets.Dark,
    /** Mirrors [ThemeRepository.useSystemAccent] — kept alongside [theme] rather than inside it
     * since it's an orthogonal flag (see `resolveEffectiveTheme`, which is what actually applies
     * it), not a property of the theme data itself. */
    val useSystemAccent: Boolean = false,
    /** Mirrors [ThemeRepository.layoutMode] — Normal vs. Grid keyboard structure, orthogonal to
     * [theme]'s color. See [dev.omakey.core.theme.LayoutMode]'s doc. */
    val layoutMode: dev.omakey.core.theme.LayoutMode = dev.omakey.core.theme.LayoutMode.NORMAL,
    val activeExtensionId: String? = null,
    val layoutSettings: LayoutSettings = LayoutSettings(),
    val fontId: String = FontChoices.SYSTEM_DEFAULT,
    val gestureSettings: GestureSettings = GestureSettings(),
    val topStripTab: TopStripTab = TopStripTab.SUGGESTIONS,
    val firstSuggestionKind: SuggestionKind = SuggestionKind.PLAIN,
    /** Mirrors the private `suggestionCycleIndex` in [KeyboardViewModel] — -1 means "nothing's
     * been cycled yet, treat index 0 as the highlighted candidate," >=0 is the actual index into
     * [suggestions] currently applied via swipe up/down cycling. The suggestion strip highlights
     * this index (falling back to 0 when -1), not always index 0. */
    val activeSuggestionIndex: Int = -1,
    /** Resolved from the focused field's [EditorInfo.imeOptions] each time a new field is
     * focused — drives both the Enter key's label (e.g. "Go", "Send") and what it actually does
     * on tap. [EditorInfo.IME_ACTION_NONE] (the default) means "just insert a newline." */
    val enterAction: Int = EditorInfo.IME_ACTION_NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** A short-lived confirmation ("hello learned"/"hello unlearned") shown as an overlay above
     * whichever extension bar content is currently active, for ~0.5s — see
     * [KeyboardViewModel.showBanner]. */
    val bannerMessage: String? = null,
    /** True while nothing typed is being remembered — either the user toggled it, or the focused
     * field is a password. Purely a rendering hint; the authority is [IncognitoPreferences]. */
    val incognito: Boolean = false,
    /** The quick-access tile panel is open, replacing the key grid. Same slot [activeExtensionId]
     * drives, and mutually exclusive with it — opening one closes the other. */
    val quickAccessOpen: Boolean = false,
    /** Drag-to-resize is armed: the keyboard draws corner handles and a Done bar, and ordinary
     * typing is suspended. What can be dragged depends on [LayoutSettings.placement]. */
    val resizing: Boolean = false,
    /** True until the language model has finished memory-mapping. Only the suggestion strip cares:
     * it is legitimately empty during a cold start, and without this that is indistinguishable from
     * "there is nothing to suggest for this word". */
    val suggestionsLoading: Boolean = false,
)
