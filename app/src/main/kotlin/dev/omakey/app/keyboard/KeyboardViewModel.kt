package dev.omakey.app.keyboard

import android.view.inputmethod.EditorInfo
import dev.omakey.core.emoji.WordEmojiSuggestions
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.input.TextEditor
import dev.omakey.core.language.InputMethodKind
import dev.omakey.core.language.LanguageDefinition
import dev.omakey.core.language.LanguagePreferences
import dev.omakey.core.language.Languages
import dev.omakey.core.language.nepali.NepaliTransliterator
import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.Calculator
import dev.omakey.core.predict.LanguageDictionaries
import dev.omakey.core.predict.PredictionEngine
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.theme.FontChoices
import dev.omakey.core.theme.FontPreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.ThemeRepository
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

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
    val layout: KeyboardLayout = Layouts.QwertyEnUS,
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
    val activeLanguageId: String = Languages.EnglishUS.id,
    val activeInputMethodId: String = Languages.EnglishUS.defaultInputMethod.id,
    /** Mirrors [LanguagePreferences]'s enabled set, resolved to full definitions — drives both
     * the Manage Languages screen and whether the suggestion-bar language-switch button shows at
     * all (hidden below 2 enabled languages, see [dev.omakey.app.keyboard.ui.KeyboardRoot]'s
     * `TopStrip`). */
    val enabledLanguages: List<LanguageDefinition> = listOf(Languages.EnglishUS),
)

/**
 * Owns the current typing session's state: active layout, shift state, suggestions, and which
 * extension panel (if any) is open. Routes all committed text through TextEditor and all
 * gesture-derived actions through the same handler as tap-derived ones, so gestures are strictly
 * additive to (not a separate path from) the tap-based key actions from M1.
 */
class KeyboardViewModel(
    private val textEditor: TextEditor,
    private val languageDictionaries: LanguageDictionaries,
    private val languagePreferences: LanguagePreferences,
    private val autocorrectPreferences: AutocorrectPreferences,
    private val predictionPreferences: PredictionPreferences,
    val extensionRegistry: ExtensionRegistry,
    themeRepository: ThemeRepository,
    layoutPreferences: LayoutPreferences,
    fontPreferences: FontPreferences,
    gesturePreferences: GesturePreferences,
    private val topStripTabPreferences: TopStripTabPreferences,
    private val scope: CoroutineScope,
    // Called with the copied/cut text right before the actual system copy/cut fires, so the host
    // service can record it into clipboard history itself and suppress its own listener's
    // primaryClip read for that one change — see OmakeyInputMethodService's clipboardListener for
    // why avoiding that read is what actually avoids the second "read your clipboard" toast.
    private val onClipboardCopy: (String) -> Unit = {},
) {
    // Resolved synchronously so the very first _uiState (below) already reflects whichever
    // language/input method was last active, rather than always starting on English and visibly
    // flipping over once the async subscription in init{} catches up. autocorrectIndex/
    // predictionEngine start as an *unloaded* placeholder for that same language (see
    // LanguageDictionaries.placeholderFor's own doc) — real dictionary data arrives moments later
    // via the init{} coroutine, same as the single English instance always used to.
    private var activeLanguage: LanguageDefinition =
        Languages.byId(languagePreferences.settings.value.activeLanguageId) ?: Languages.EnglishUS
    private var activeInputMethodId: String =
        languagePreferences.settings.value.inputMethodByLanguage[activeLanguage.id] ?: activeLanguage.defaultInputMethod.id
    private var autocorrectIndex: AutocorrectIndex
    private var predictionEngine: PredictionEngine

    init {
        val (index, engine) = languageDictionaries.placeholderFor(activeLanguage)
        autocorrectIndex = index
        predictionEngine = engine
    }

    private fun activeInputMethod() = activeLanguage.inputMethods.find { it.id == activeInputMethodId } ?: activeLanguage.defaultInputMethod

    private val _uiState = MutableStateFlow(
        KeyboardUiState(
            layout = activeInputMethod().layout,
            theme = themeRepository.currentTheme.value,
            useSystemAccent = themeRepository.useSystemAccent.value,
            layoutMode = themeRepository.layoutMode.value,
            layoutSettings = layoutPreferences.settings.value,
            fontId = fontPreferences.fontId.value,
            gestureSettings = gesturePreferences.settings.value,
            topStripTab = topStripTabPreferences.tab.value,
            activeLanguageId = activeLanguage.id,
            activeInputMethodId = activeInputMethodId,
            enabledLanguages = languagePreferences.settings.value.enabledLanguageIds.mapNotNull { Languages.byId(it) },
        ),
    )
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    private var lastCommittedWord: String? = null

    /** Whatever [lastCommittedWord] was immediately *before* the current [lastCommittedWord] —
     * i.e. the bigram context for the word that was just finished. Needed to rank real-word
     * alternatives by what actually fits the surrounding sentence (see [refreshSuggestions]).
     * Captured before [lastCommittedWord] is overwritten by the next word. */
    private var previousToLastCommittedWord: String? = null

    /** The literal separator text that ended [lastCommittedWord] — a space, a newline (Enter
     * inserting one rather than firing an editor action), or a punctuation character. Needed by
     * [ActiveCorrection] (`RETROACTIVE` mode) to know exactly how many characters sit between the
     * target word and the cursor, and what to retype after it; hardcoding a space there would
     * silently corrupt "word.<fix>" into "word.<fix> " (extra space) or "word\n<fix>" into
     * "word\n<fix> " (newline replaced by a space). */
    private var lastWordBoundarySeparator: String = " "
    private var currentWordBuffer = StringBuilder()

    /** Raw Latin keystrokes typed so far for the word currently being composed under a
     * TRANSLITERATED input method (e.g. Nepali Romanized) — parallel to [currentWordBuffer], which
     * tracks what's actually *committed* (the transliterated Devanagari). Cleared at the same word
     * boundaries [currentWordBuffer] is. See [onCharacterKeyTap]/[commitTypedText]. */
    private var romanizedRawBuffer = StringBuilder()

    /** How many UTF-16 chars of [romanizedRawBuffer]'s current transliteration are presently
     * committed in the text field — what a re-render (typing/backspacing within the same word)
     * needs to delete before committing the freshly recomputed text. Reset to 0 at the same word
     * boundaries [romanizedRawBuffer] is. */
    private var romanizedCommittedLength = 0

    /** When the most recent space was actually committed — 0 means "none yet this session, or
     * already consumed by a double-tap conversion." Powers [onSpace]'s double-tap-space-for-
     * period detection (`AutocorrectSettings.doubleTapSpaceForPeriod`); see that function's own
     * doc for why this is time-based rather than counting taps. */
    private var lastSpaceCommitAtMs: Long = 0L

    /** -1 = not currently cycling (buffer holds what was actually typed, or nothing's been
     * cycled yet); >=0 = index into the frozen suggestions snapshot currently applied, via swipe
     * up/down. Kept in sync with [KeyboardUiState.activeSuggestionIndex] via
     * [setSuggestionCycleIndex] — every assignment site goes through that function (not a plain
     * `=`), since the suggestion strip needs to know which candidate is actually applied to
     * highlight it. Real bug, fixed: this used to be a private field the UI never saw, so
     * `SuggestionsTabContent` always highlighted index 0 regardless of which candidate cycling
     * had actually landed on. */
    private var suggestionCycleIndex = -1
        set(value) {
            field = value
            _uiState.update { it.copy(activeSuggestionIndex = value) }
        }
    private var refreshJob: Job? = null

    /** Set the moment an autocorrect swap fires, cleared by anything else. Lets the very next
     * backspace revert to what was actually typed (Gboard/iOS convention) instead of just
     * deleting one character of the "fixed" word — checked in onDeleteCharacter(). */
    private data class AutocorrectRecord(val original: String, val corrected: String)
    private var lastAutocorrect: AutocorrectRecord? = null

    /** Set the moment a backspace reverts an autocorrect swap back to what was actually typed;
     * cleared alongside [lastAutocorrect] everywhere else. Stops the very next word-boundary
     * commit (e.g. pressing space right after the revert) from immediately re-correcting the
     * same word right back to the version the user just explicitly rejected. */
    private var revertedWord: String? = null

    /** Word/character-level undo/redo (Tools tab "Undo"/"Redo", also wired to Ctrl+Z/Ctrl+Shift+Z-
     * equivalent gestures) — scoped to raw text mutation: a word finishing (space/punctuation/
     * Enter), a word being deleted (swipe-left), and plain character-by-character backspacing
     * through already-committed text. Autocorrect/suggestion corrections already have their own
     * dedicated, more precise revert gestures (backspace-reverts-the-swap, swipe up/down cycling)
     * — folding those into this same generic stack would fight with that existing, better-suited
     * machinery rather than complement it. Android's `InputConnection` has no standardized
     * cross-app undo API (`performContextMenuAction(android.R.id.undo)` isn't reliably
     * implemented by host apps), so this is necessarily omakey's own app-level history, not a
     * passthrough to the host app's.
     *
     * Both variants store the *exact* text involved — [Inserted.text] is the finished word plus
     * whatever real separator followed it (space, punctuation, or newline; never assumed to be a
     * plain space, which used to silently corrupt/drop it on undo — a real bug), and
     * [Deleted.text] is exactly what [TextEditor.deleteWordBackward] removed (word plus whatever
     * whitespace it actually consumed) or a single backspaced character. Undo/redo just
     * delete-back/retype that literal string, so nothing is inferred or reconstructed wrong. */
    private sealed class UndoEvent {
        data class Inserted(val text: String) : UndoEvent()
        data class Deleted(val text: String) : UndoEvent()
    }
    private val undoStack = ArrayDeque<UndoEvent>()
    private val redoStack = ArrayDeque<UndoEvent>()

    /** True while the most recent action was a single plain backspace into already-committed
     * text (see the last branch of [onDeleteCharacter]) — lets [recordPlainCharDelete] merge a
     * whole backspace run into one undo step, matching how a run of backspaces reads as "one
     * edit" in every mainstream text editor rather than needing one Ctrl+Z per character. Reset
     * to false by every other action that mutates text or moves the cursor. */
    private var deleteCoalesceActive = false

    /** How to apply whichever `suggestions[index]` the user swipes/taps to, for a word currently
     * "in focus" for the strip — unifying the three different ways a word gets there so cycling
     * (repeated swipe up/down through the *same* frozen list) works identically regardless of
     * which one. [occupiedBefore]/[occupiedAfter] track how many characters *currently* sit where
     * the word is (updated after every cycle step, since each candidate can be a different
     * length) — not the original word's length, except before the first step. */
    private enum class CorrectionApplyMode {
        /** Word is still being actively typed ([currentWordBuffer] is it) — delete the buffer,
         * retype, leave it open for further editing (matches ordinary completion-cycling). */
        LIVE_BUFFER,

        /** Word was just finished (space/punctuation/Enter already committed [lastWordBoundarySeparator]
         * right after it) — delete back through the word *and* the separator, retype both. */
        RETROACTIVE,

        /** Cursor is sitting inside an already-committed word reached by navigation, not typing
         * (tap, arrow keys, ...) — delete around the cursor via [TextEditor.replaceWordAtCursor].
         * After the first replacement, [occupiedAfter] is always 0 (the replacement lands fully
         * before the cursor), so further cycles behave like [RETROACTIVE] without a separator. */
        CURSOR,
    }

    private data class ActiveCorrection(
        val mode: CorrectionApplyMode,
        val originalWord: String,
        var occupiedBefore: Int,
        var occupiedAfter: Int,
        val separator: String,
    )
    private var activeCorrection: ActiveCorrection? = null

    init {
        // Keeps an already-open keyboard in sync if the user changes theme/layout settings from
        // Settings while the IME view is alive (same process, different Activity).
        themeRepository.currentTheme
            .onEach { theme -> _uiState.update { it.copy(theme = theme) } }
            .launchIn(scope)
        themeRepository.useSystemAccent
            .onEach { enabled -> _uiState.update { it.copy(useSystemAccent = enabled) } }
            .launchIn(scope)
        themeRepository.layoutMode
            .onEach { mode -> _uiState.update { it.copy(layoutMode = mode) } }
            .launchIn(scope)
        layoutPreferences.settings
            .onEach { settings -> _uiState.update { it.copy(layoutSettings = settings) } }
            .launchIn(scope)
        fontPreferences.fontId
            .onEach { id -> _uiState.update { it.copy(fontId = id) } }
            .launchIn(scope)
        gesturePreferences.settings
            .onEach { settings -> _uiState.update { it.copy(gestureSettings = settings) } }
            .launchIn(scope)
        languagePreferences.settings
            .onEach { settings -> applyLanguageSettings(settings) }
            .launchIn(scope)
    }

    /** Reacts to [LanguagePreferences] changes — which languages are enabled, which is active,
     * and which input method each uses. Only does real work when the *active* language or its
     * input method actually changed (an unrelated enable/disable elsewhere in the list still
     * updates [KeyboardUiState.enabledLanguages], just without touching layout/dictionaries). */
    private fun applyLanguageSettings(settings: dev.omakey.core.language.LanguageSettings) {
        val enabled = settings.enabledLanguageIds.mapNotNull { Languages.byId(it) }
        val language = Languages.byId(settings.activeLanguageId) ?: Languages.EnglishUS
        val inputMethodId = settings.inputMethodByLanguage[language.id] ?: language.defaultInputMethod.id
        val languageChanged = language.id != activeLanguage.id
        val inputMethodChanged = inputMethodId != activeInputMethodId
        activeLanguage = language
        activeInputMethodId = inputMethodId
        _uiState.update {
            it.copy(
                enabledLanguages = enabled,
                activeLanguageId = language.id,
                activeInputMethodId = inputMethodId,
                layout = if (languageChanged || inputMethodChanged) activeInputMethod().layout else it.layout,
            )
        }
        if (!languageChanged && !inputMethodChanged) return
        // Whatever was mid-typing under the old language/input method doesn't carry over — a
        // half-composed transliteration buffer or an in-progress correction cycle makes no sense
        // once the input method underneath it has changed. Real committed text and undo history
        // are untouched (those stay valid regardless of what typed them).
        currentWordBuffer.clear()
        romanizedRawBuffer.clear()
        romanizedCommittedLength = 0
        suggestionCycleIndex = -1
        lastAutocorrect = null
        revertedWord = null
        activeCorrection = null
        _uiState.update { it.copy(suggestions = emptyList(), emojiSuggestions = emptyList(), firstSuggestionKind = SuggestionKind.PLAIN) }
        if (languageChanged) {
            scope.launch {
                val (index, engine) = languageDictionaries.forLanguage(language)
                autocorrectIndex = index
                predictionEngine = engine
            }
        }
    }

    /** Advances to the next enabled language (wrapping around), in [KeyboardUiState.enabledLanguages]'s
     * order — the suggestion-bar language-switch button's action (see `KeyboardRoot.kt`'s
     * `TopStrip`). A no-op with fewer than 2 enabled languages, same as the button being hidden
     * then. */
    fun cycleActiveLanguage() {
        val enabled = _uiState.value.enabledLanguages
        if (enabled.size < 2) return
        val currentIndex = enabled.indexOfFirst { it.id == activeLanguage.id }.coerceAtLeast(0)
        val next = enabled[(currentIndex + 1) % enabled.size]
        languagePreferences.setActiveLanguage(next.id)
    }

    val extensionHost = object : ExtensionHost {
        override fun insertText(text: String) {
            text.forEach { textEditor.commitCharacter(it) }
        }
        override fun close() {
            _uiState.update { it.copy(activeExtensionId = null) }
        }
    }

    /** [info] is the newly-focused field's [EditorInfo] (null if unavailable) — resolves what the
     * Enter key should do in this field. A field that opts out of an enter action
     * ([EditorInfo.IME_FLAG_NO_ENTER_ACTION]) or simply doesn't declare one always gets a plain
     * newline; anything else (Go/Search/Send/Next/Done/Previous, e.g. a URL bar's "Go") is passed
     * straight through to [TextEditor.sendEditorAction] instead. */
    fun resetForNewField(info: EditorInfo? = null) {
        val enterAction = when {
            info == null -> EditorInfo.IME_ACTION_NONE
            (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 -> EditorInfo.IME_ACTION_NONE
            else -> info.imeOptions and EditorInfo.IME_MASK_ACTION
        }
        _uiState.update {
            it.copy(
                layout = activeInputMethod().layout,
                // See maybeAutoCapitalize's doc — auto-capitalize doesn't apply under a
                // TRANSLITERATED input method, where shift means something else entirely.
                shiftOn = activeInputMethod().kind != InputMethodKind.TRANSLITERATED &&
                    autocorrectPreferences.settings.value.autoCapitalizeEnabled && textEditor.textBeforeCursor(1).isEmpty(),
                capsLockOn = false,
                suggestions = emptyList(),
                emojiSuggestions = emptyList(),
                firstSuggestionKind = SuggestionKind.PLAIN,
                activeExtensionId = null,
                // Deliberately NOT reset to SUGGESTIONS here — the whole point of persisting it
                // (TopStripTabPreferences) is that it survives ordinary field-to-field navigation,
                // not just app relaunches. Whatever's already in uiState.topStripTab stays.
                enterAction = enterAction,
                canUndo = false,
                canRedo = false,
            )
        }
        lastCommittedWord = null
        previousToLastCommittedWord = null
        currentWordBuffer.clear()
        romanizedRawBuffer.clear()
        romanizedCommittedLength = 0
        suggestionCycleIndex = -1
        lastAutocorrect = null
        revertedWord = null
        activeCorrection = null
        // A new field is a new editing context — undo history from whatever was focused before
        // isn't meaningful here, same lifecycle as every other per-field piece of state above.
        undoStack.clear()
        redoStack.clear()
    }

    fun selectTopStripTab(tab: TopStripTab) {
        _uiState.update { it.copy(topStripTab = tab) }
        topStripTabPreferences.setTab(tab)
    }

    fun onSelectAll() = textEditor.selectAll()

    fun onCopy() {
        textEditor.selectedText()?.let(onClipboardCopy)
        textEditor.copySelection()
    }

    fun onCut() {
        textEditor.selectedText()?.let(onClipboardCopy)
        textEditor.cutSelection()
    }

    fun onPaste() = textEditor.pasteFromClipboard()

    fun onKeyTap(code: Int) {
        when (code) {
            SpecialKeyCode.SHIFT -> toggleShift()
            SpecialKeyCode.BACKSPACE -> onDeleteCharacter()
            SpecialKeyCode.SPACE -> onSpace()
            SpecialKeyCode.ENTER -> onEnter()
            SpecialKeyCode.SYMBOLS -> switchLayout(
                when (_uiState.value.layout.id) {
                    Layouts.Symbols1.id -> Layouts.Symbols2
                    Layouts.Symbols2.id -> Layouts.Symbols1
                    else -> Layouts.Symbols1
                },
            )
            SpecialKeyCode.LETTERS -> switchLayout(activeInputMethod().layout)
            SpecialKeyCode.EXTENSIONS -> toggleExtensionPanel()
            else -> onCharacter(code)
        }
    }

    /** Tap (or held-with-no-popup long-press) on a CHARACTER key whose output isn't a plain
     * single Unicode codepoint transformable via `Char.uppercaseChar()` — either a multi-
     * codepoint cluster ([KeyDefinition.text], e.g. Nepali Traditional's conjunct keys) or a real
     * independent shift-layer value ([KeyDefinition.shiftedText]) rather than a case mapping.
     * Resolved once at the UI dispatch layer (see `KeyboardRoot.handleGestureEvent`) since that's
     * the only place the full [KeyDefinition] — not just its bare `Int` code — is available;
     * [onKeyTap] stays the path for every ordinary single-codepoint key, unchanged. */
    fun onCharacterKeyTap(key: KeyDefinition) {
        val shiftActive = _uiState.value.shiftOn || _uiState.value.capsLockOn
        val shiftedText = key.shiftedText
        val text = if (shiftActive && shiftedText != null) shiftedText else (key.text ?: key.label)
        commitTypedText(text, fromTransliteratedKey = false)
    }

    fun onSwipeLeft() = onDeleteWord()
    fun onSwipeRight() = onSpace()

    /** Long-press-and-drag on the spacebar (see `KeyGrid`'s gesture loop in `KeyboardRoot.kt`) —
     * synthesizes a DPAD key event rather than tracking an absolute cursor position, which works
     * uniformly across every host app's InputConnection without needing to know the field's total
     * text length (something [TextEditor] deliberately doesn't expose — see its own doc comment,
     * only a windowed 128-char cursor context). Whatever is currently buffered as "still being
     * typed" is flushed first — cursor movement always means the word at the old position is
     * done, the same as any other word-boundary action (space, punctuation, Enter). Doesn't touch
     * suggestions itself — the DPAD event moves the host app's real cursor, which fires
     * `onUpdateSelection` -> [onCursorMoved] the same as a tap would, and that's what refreshes
     * the strip. */
    fun moveCursor(forward: Boolean) {
        maybeAutocorrectBufferedWord()
        flushWordBuffer()
        textEditor.sendKeyEvent(if (forward) android.view.KeyEvent.KEYCODE_DPAD_RIGHT else android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        suggestionCycleIndex = -1
        deleteCoalesceActive = false
    }

    /** Cycles left through the frozen suggestions snapshot (does not re-query, so the candidate
     * set stays stable while cycling). At the leftmost candidate — or when there's nothing to
     * cycle at all — restores the original word and, if it's still actively being typed and not
     * already a known word, saves it to the local dictionary (see [revertAndMaybeSave]). */
    fun onSwipeUp() {
        val suggestions = _uiState.value.suggestions
        when {
            suggestions.isEmpty() -> revertAndMaybeSave()
            // Not cycling yet — the word on screen is still exactly what was typed, so there's
            // nothing "up"/before it to cycle back to. Swiping up here means "keep it as typed,"
            // i.e. save (see revertAndMaybeSave), not "jump to the first suggestion" (that's what
            // swipe down is for — a real bug, previously identical to suggestionCycleIndex == -1
            // falling through to applySuggestion(0), which silently replaced the typed word).
            suggestionCycleIndex <= 0 -> revertAndMaybeSave()
            else -> applySuggestion(suggestionCycleIndex - 1)
        }
    }

    /** Cycles right through the frozen suggestions snapshot; clamps at the last candidate. Unless
     * "Double space + swipe down for comma" is on and this swipe-down lands right after a space
     * (the same timing window [shouldConvertDoubleSpaceToPeriod] uses), in which case it converts
     * that space into a comma instead — the comma-equivalent of double-tap-space-for-period, just
     * triggered by a different second action. */
    fun onSwipeDown() {
        if (shouldConvertSpaceToCommaOnSwipeDown()) {
            convertPrecedingSpaceToComma()
            return
        }
        val suggestions = _uiState.value.suggestions
        if (suggestions.isEmpty()) return
        applySuggestion((suggestionCycleIndex + 1).coerceIn(0, suggestions.size - 1))
    }

    private fun applySuggestion(index: Int) {
        val word = _uiState.value.suggestions.getOrNull(index) ?: return
        lastAutocorrect = null
        revertedWord = null
        deleteCoalesceActive = false
        if (activeCorrection != null) {
            applyActiveCorrection(word)
            suggestionCycleIndex = index
            return
        }
        // No word "in focus" being corrected/varied — this suggestion is a plain next-word
        // prediction instead, typed fresh (matches the pre-swipe-cycling behavior: left open for
        // further editing, no trailing space, unlike tap-accepting one via onSuggestionAccepted).
        val wasSplit = commitCorrection(word)
        if (wasSplit) {
            suggestionCycleIndex = -1
            refreshSuggestions()
        } else {
            suggestionCycleIndex = index
        }
    }

    /** Reached when there's nothing further "left" to cycle to (already showing the first
     * suggestion, or there were none at all): restores whatever was originally there — undoing
     * any candidate currently applied, via the same [applyActiveCorrection] every other cycle
     * step uses, so further swipes keep working normally afterward — and offers to save/unsave it
     * as a known word, showing a brief banner either way.
     *
     * Previously gated to `LIVE_BUFFER` mode only (word still being typed) — that excluded the
     * two other, arguably more common ways a word ends up "in focus" for the strip (finished and
     * re-suggested via `RETROACTIVE`, or reached by tapping the cursor into it via `CURSOR`),
     * which is why swipe-up-to-save read as "not working" for most real usage. There's no actual
     * reason learning should care how the word got into focus, only whether it's a real word the
     * user wants remembered — so this now applies uniformly to any mode. */
    private fun revertAndMaybeSave() {
        val active = activeCorrection
        // currentWordBuffer is already empty once a trailing separator (space, punctuation) has
        // flushed it — e.g. cursor sitting right after "bibek " with nothing typed since. Falling
        // back to wordBeforeCursor() recovers "bibek" (not "bibek " — its separator is reported
        // separately and never included) instead of silently no-op'ing the swipe-up-to-save.
        val original = active?.originalWord
            ?: currentWordBuffer.toString().ifBlank { textEditor.wordBeforeCursor()?.word.orEmpty() }
        if (original.isBlank()) return
        if (active != null) applyActiveCorrection(original)
        suggestionCycleIndex = -1
        deleteCoalesceActive = false
        when {
            autocorrectIndex.isUserAdded(original) -> {
                autocorrectIndex.unlearn(original)
                scope.launch { predictionEngine.deleteWord(original) }
                showBanner("$original unlearned")
            }
            !autocorrectIndex.isKnown(original) -> {
                autocorrectIndex.learn(original)
                scope.launch { predictionEngine.saveWord(original) }
                showBanner("$original learned")
            }
            // Already known and not user-added (i.e. a bundled dictionary word) — nothing to
            // learn or unlearn, so no banner; swiping up on an ordinary real word is a plain
            // "keep it as typed" with no side effect, same as it always was.
        }
    }

    private var bannerJob: Job? = null

    /** Flashes [message] in the suggestion strip's slot for ~0.5s, matching the plan's ask for a
     * short learn/unlearn confirmation rather than a system Toast (which would interrupt typing
     * flow and, per Android 12+, may not even be visible while a keyboard has focus). */
    private fun showBanner(message: String) {
        bannerJob?.cancel()
        _uiState.update { it.copy(bannerMessage = message) }
        bannerJob = scope.launch {
            delay(500)
            _uiState.update { it.copy(bannerMessage = null) }
        }
    }

    /** Replaces whatever partial word is currently buffered/committed with the accepted
     * suggestion, then finishes it (adds a trailing space) — tapping means "I'm done with this
     * word," unlike swipe-accepting via [applySuggestion]/[onSwipeUp]/[onSwipeDown], which leaves
     * a still-being-typed word open for further editing. Does not learn/record anything (see
     * [flushWordBuffer]'s doc) — the word came from the suggestion strip, so it was already a
     * known word or an already-vetted correction. */
    fun onSuggestionAccepted(word: String) {
        lastAutocorrect = null
        revertedWord = null
        deleteCoalesceActive = false
        val active = activeCorrection
        if (active != null) {
            applyActiveCorrection(word)
            if (active.mode == CorrectionApplyMode.LIVE_BUFFER) {
                // "Done with this word" — close it out with a trailing space, same as accepting a
                // plain next-word prediction below. RETROACTIVE/CURSOR corrections are already
                // sitting in finished text; nothing more to close out for those.
                val finished = currentWordBuffer.toString()
                if (finished.isNotEmpty()) {
                    textEditor.commitCharacter(' ')
                    currentWordBuffer.clear()
                    previousToLastCommittedWord = lastCommittedWord
                    lastCommittedWord = finished.lowercase()
                }
            }
            suggestionCycleIndex = -1
            refreshSuggestions()
            return
        }
        commitCorrection(word)
        val finished = currentWordBuffer.toString()
        textEditor.commitCharacter(' ')
        currentWordBuffer.clear()
        previousToLastCommittedWord = lastCommittedWord
        lastCommittedWord = finished.lowercase()
        refreshSuggestions()
    }

    /** Applies [replacement] wherever the currently-tracked [activeCorrection] says the word in
     * focus actually is, updating its occupied-character counts for whatever the *next* cycle
     * step needs (each candidate can be a different length than the last). Handles [replacement]
     * containing a single embedded space (a "missing space" split fix, e.g. "this is" — see
     * [AutocorrectIndex.alternatives]) only in [CorrectionApplyMode.LIVE_BUFFER]: the embedded
     * space is a real word boundary, so the first word is committed there and then (bookkeeping
     * only, no learning — see [flushWordBuffer]'s doc) while the second becomes the new live
     * buffer, ending this cycling session — a split mid-sentence via RETROACTIVE/CURSOR modes
     * isn't a case [AutocorrectIndex.alternatives] produces, so it isn't handled here. */
    private fun applyActiveCorrection(replacement: String) {
        val active = activeCorrection ?: return
        when (active.mode) {
            CorrectionApplyMode.LIVE_BUFFER -> {
                repeat(active.occupiedBefore) { textEditor.deleteCharacterBackward() }
                val spaceIndex = replacement.indexOf(' ')
                if (spaceIndex <= 0 || spaceIndex == replacement.length - 1) {
                    replacement.forEach { textEditor.commitCharacter(it) }
                    currentWordBuffer.clear()
                    currentWordBuffer.append(replacement)
                    active.occupiedBefore = replacement.length
                } else {
                    val firstWord = replacement.substring(0, spaceIndex)
                    val secondWord = replacement.substring(spaceIndex + 1)
                    firstWord.forEach { textEditor.commitCharacter(it) }
                    textEditor.commitCharacter(' ')
                    previousToLastCommittedWord = lastCommittedWord
                    lastCommittedWord = firstWord.lowercase()
                    secondWord.forEach { textEditor.commitCharacter(it) }
                    currentWordBuffer.clear()
                    currentWordBuffer.append(secondWord)
                    activeCorrection = null
                }
            }
            CorrectionApplyMode.RETROACTIVE -> {
                repeat(active.occupiedBefore + active.separator.length) { textEditor.deleteCharacterBackward() }
                replacement.forEach { textEditor.commitCharacter(it) }
                active.separator.forEach { textEditor.commitCharacter(it) }
                lastCommittedWord = replacement.lowercase()
                active.occupiedBefore = replacement.length
            }
            CorrectionApplyMode.CURSOR -> {
                textEditor.replaceWordAtCursor(
                    TextEditor.WordAtCursor(active.originalWord, active.occupiedBefore, active.occupiedAfter),
                    replacement,
                )
                active.occupiedBefore = replacement.length
                active.occupiedAfter = 0
            }
        }
    }

    /** Deletes the currently-buffered raw text and commits [replacement] in its place — the
     * fallback path when there's no [activeCorrection] tracked, i.e. [replacement] is a plain
     * next-word prediction rather than a fix/variant of a specific word (see
     * [AutocorrectIndex.alternatives]), and also used by [maybeAutocorrectBufferedWord] for the
     * silent, automatic correction. Also the one remaining place that still special-cases a
     * two-word split result on its own (see [applyActiveCorrection]'s doc for why the two paths
     * don't share that handling directly): a "missing space" split correction, e.g. "thisbis" ->
     * "this is" — the embedded space is a real word boundary, so the first word is committed and
     * flushed (bookkeeping only, no learning) while the second becomes the new active buffer.
     * Returns true if [replacement] was a two-word split, false for a plain single word. */
    private fun commitCorrection(replacement: String): Boolean {
        repeat(currentWordBuffer.length) { textEditor.deleteCharacterBackward() }
        currentWordBuffer.clear()

        val spaceIndex = replacement.indexOf(' ')
        if (spaceIndex <= 0 || spaceIndex == replacement.length - 1) {
            replacement.forEach { textEditor.commitCharacter(it) }
            currentWordBuffer.append(replacement)
            return false
        }

        val firstWord = replacement.substring(0, spaceIndex)
        val secondWord = replacement.substring(spaceIndex + 1)
        firstWord.forEach { textEditor.commitCharacter(it) }
        textEditor.commitCharacter(' ')
        previousToLastCommittedWord = lastCommittedWord
        lastCommittedWord = firstWord.lowercase()
        secondWord.forEach { textEditor.commitCharacter(it) }
        currentWordBuffer.append(secondWord)
        return true
    }

    /** Called whenever the cursor/selection changes for a reason outside the normal typing flow —
     * a tap elsewhere in the text, arrow-key navigation, autofill, etc (see
     * `OmakeyInputMethodService.onUpdateSelection`). Independent of [currentWordBuffer]'s typing-
     * order tracking entirely: derives "the word at the cursor" straight from the live text via
     * [TextEditor.wordAtCursor] and looks up alternatives for *that* word, which is the only way
     * to catch "cursor moved into the middle of an already-committed word" — nothing about normal
     * keystroke handling ever sees that case, since it isn't a keystroke at all. */
    fun onCursorMoved() {
        val wordAtCursor = textEditor.wordAtCursor()
        // The cursor sitting right at the end of a word that exactly matches what's actively
        // being typed is the ordinary, extremely common case (every keystroke moves the cursor)
        // — already handled by the normal typing pipeline (refreshSuggestions already ran for
        // it), so this only needs to act when the cursor is somewhere *else*.
        val isOrdinaryTypingPosition = wordAtCursor != null &&
            wordAtCursor.charsAfterCursor == 0 &&
            wordAtCursor.word == currentWordBuffer.toString()
        if (wordAtCursor == null || isOrdinaryTypingPosition) {
            if (activeCorrection?.mode == CorrectionApplyMode.CURSOR) {
                activeCorrection = null
                refreshSuggestions()
            }
            return
        }

        // Deliberately NOT gated on autocorrectEnabled — that toggle controls only the silent,
        // automatic correction in maybeAutocorrectBufferedWord(). Every suggestion-strip
        // alternative (this one included) is manually swipe/tap-accepted, so it stays available
        // regardless of whether auto-apply is on.
        //
        // wordAlternatives runs a Damerau-Levenshtein scan (see AutocorrectIndex.alternatives) —
        // cheap on modern hardware but not free, and onCursorMoved fires synchronously on the IME's
        // main thread for *every* selection change, including our own commits while cycling
        // suggestions via swipe up/down (each cycle step moves the cursor, which re-triggers this).
        // Running the scan inline here was blocking the UI thread on every one of those moves and
        // made correction-cycling visibly hang on slower/older devices — offloaded the same way
        // refreshSuggestions() already does for the typing path.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val alternatives = withContext(Dispatchers.Default) { wordAlternatives(wordAtCursor.word) }
            if (alternatives.isEmpty()) {
                if (activeCorrection?.mode == CorrectionApplyMode.CURSOR) {
                    activeCorrection = null
                    refreshSuggestions()
                }
                return@launch
            }
            activeCorrection = ActiveCorrection(
                mode = CorrectionApplyMode.CURSOR,
                originalWord = wordAtCursor.word,
                occupiedBefore = wordAtCursor.charsBeforeCursor,
                occupiedAfter = wordAtCursor.charsAfterCursor,
                separator = "",
            )
            suggestionCycleIndex = -1
            _uiState.update { it.copy(suggestions = alternatives, firstSuggestionKind = SuggestionKind.CORRECTION) }
        }
    }

    private fun onCharacter(code: Int) {
        // Defensive no-op: `code` should always be a real Unicode codepoint here (every ordinary
        // English/Symbols key, plus Nepali Romanized's plain QwertyEnUS letters), but Nepali
        // Traditional's multi-codepoint keys use synthetic negative codes that are never meant to
        // reach this path (see NepaliLayouts's doc — they route through onCharacterKeyTap
        // instead). Guards against a missed dispatch site crashing Character.toChars rather than
        // just silently dropping the tap.
        if (!Character.isValidCodePoint(code)) return
        val char = Character.toChars(code)[0]
        if (activeInputMethod().kind == InputMethodKind.TRANSLITERATED) {
            if (char.isLetter()) {
                onRomanizedLetterKey(char)
            } else {
                flushRomanizedWord()
                commitTypedText(char.toString(), fromTransliteratedKey = true)
            }
        } else {
            commitTypedText(char.toString(), fromTransliteratedKey = false)
        }
    }

    /** Also used for accent-popup selections (long-press on a key with popupChars), which arrive
     * as the selected option's own text rather than a key code since accent/punctuation variants
     * aren't part of the base layout — usually one character, but not always (e.g. Nepali
     * Traditional's danda key offers "रु," a 2-codepoint popup option). */
    fun onAccentSelected(text: String) {
        if (text.isEmpty()) return
        if (activeInputMethod().kind == InputMethodKind.TRANSLITERATED && text.length == 1 && text[0].isLetter()) {
            onRomanizedLetterKey(text[0])
        } else {
            commitTypedText(text, fromTransliteratedKey = false)
        }
    }

    /** One Latin keystroke while a TRANSLITERATED input method (Nepali Romanized) is composing a
     * word: appends to [romanizedRawBuffer], re-renders the *whole* buffer through
     * [NepaliTransliterator] (never incremental — see its own doc for why), and swaps whatever was
     * previously committed for this word for the fresh rendering — the same delete-then-recommit
     * idiom [applyActiveCorrection]'s `LIVE_BUFFER` mode already uses elsewhere in this file.
     * [currentWordBuffer] is kept mirroring the *committed* (Devanagari) text throughout, exactly
     * the role it already plays for a [InputMethodKind.DIRECT] input method — undo/suggestion
     * machinery downstream doesn't need to know transliteration happened at all. */
    private fun onRomanizedLetterKey(rawChar: Char) {
        suggestionCycleIndex = -1
        deleteCoalesceActive = false
        val char = if (_uiState.value.shiftOn) rawChar.uppercaseChar() else rawChar
        romanizedRawBuffer.append(char)
        val rendered = NepaliTransliterator.transliterate(romanizedRawBuffer.toString())
        repeat(romanizedCommittedLength) { textEditor.deleteCharacterBackward() }
        textEditor.insertText(rendered)
        romanizedCommittedLength = rendered.length
        currentWordBuffer.clear()
        currentWordBuffer.append(rendered)
        refreshSuggestions()
        if (_uiState.value.shiftOn && !_uiState.value.capsLockOn) {
            _uiState.update { it.copy(shiftOn = false) }
        }
    }

    /** Word-boundary equivalent of [flushWordBuffer] for the Romanized buffering state — resets
     * [romanizedRawBuffer]/[romanizedCommittedLength] and then defers to the ordinary
     * [flushWordBuffer] (fed by [currentWordBuffer], which already mirrors whatever Devanagari
     * text is actually committed) for the shared undo/`lastCommittedWord` bookkeeping. */
    private fun flushRomanizedWord() {
        romanizedRawBuffer.clear()
        romanizedCommittedLength = 0
        flushWordBuffer(separator = "")
    }

    /** Commits [text] (one or more Unicode codepoints — a single Latin letter for English, or a
     * whole Devanagari cluster for a Nepali Traditional key) at the cursor and updates word-
     * buffer/suggestion/undo state accordingly. Whether [text] continues the current word or ends
     * it (like `.isLetter()` used to decide for a single Latin `Char`) is judged per-codepoint by
     * Unicode general category instead — letters *and* combining marks (Devanagari matras/virama)
     * both continue a word, matching `.isLetter()` exactly for every existing English/Symbols key
     * while correctly extending to Nepali Traditional's clusters, which `.isLetter()` alone would
     * wrongly treat as punctuation (a bare virama's category is "mark," not "letter"). */
    private fun commitTypedText(text: String, fromTransliteratedKey: Boolean) {
        suggestionCycleIndex = -1
        deleteCoalesceActive = false
        val shifted = if (_uiState.value.shiftOn && text.length == 1) text.uppercase() else text
        val isWordContinuing = shifted.isNotEmpty() && shifted.codePoints().allMatch { cp -> isWordContinuingCodepoint(cp) }
        // Punctuation typed directly after a word (no space) is a word boundary too — correct
        // before committing it, so it lands after the fixed word. Not meaningful mid-transliteration
        // (Nepali has no autocorrect dictionary yet — see LanguageDefinition.dictionaryAsset).
        if (!isWordContinuing && !fromTransliteratedKey) {
            maybeAutocorrectBufferedWord()
        }
        textEditor.insertText(shifted)
        if (isWordContinuing) {
            currentWordBuffer.append(shifted)
            refreshSuggestions()
        } else {
            flushWordBuffer(separator = shifted)
            lastWordBoundarySeparator = shifted
            refreshSuggestions(checkContextualCorrection = true)
            if (shifted == "=") tryShowCalculatorResult()
        }
        if (_uiState.value.shiftOn && !_uiState.value.capsLockOn) {
            _uiState.update { it.copy(shiftOn = false) } // one-shot shift, matches typical mobile keyboard behavior
        }
    }

    private fun isWordContinuingCodepoint(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(),
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        -> true
        else -> false
    }

    /** Inline calculator — typing "12+7=" offers the full "12+7=19" in the suggestion strip
     * (never auto-applied, same convention as every other correction), so what you tap reads as
     * a complete, self-explanatory answer rather than a bare number floating with no context.
     * Tapping it deletes the typed "12+7=" and retypes "12+7=19" in its place — same
     * [CorrectionApplyMode.RETROACTIVE] mechanism as every other correction, so on screen the net
     * effect is just the missing "19" getting appended.
     *
     * Called right after '=' itself has already been committed as ordinary punctuation (see
     * [commitTypedText]), so [textBeforeCursor] already includes it — but also re-derived from
     * [refreshSuggestionsAfterDeletion] on every backspace, not just fresh '=' keystrokes. Real
     * bug, fixed: this used to be a one-shot side effect of typing '=', with nothing re-running
     * it afterward — backspacing away just the applied result (leaving the cursor sitting right
     * after "12+7=" again) fell through to the plain word-suggestion logic, which has no idea
     * what a calculator expression is, silently dropping the suggestion and forcing the whole
     * expression to be retyped from scratch to get it back. Returns whether a suggestion was
     * actually shown, so deletion-refresh callers know whether to fall through to their own
     * (non-calculator) suggestion logic instead.
     *
     * Scoped to plain `+ - * /` per [Calculator]'s own doc — deliberately not reusing
     * [currentWordBuffer] (letters-only, never sees digits/operators in the first place), a
     * separate read of the actual committed text instead. */
    private fun tryShowCalculatorResult(): Boolean {
        val textBefore = textEditor.textBeforeCursor(64)
        if (textBefore.isEmpty() || textBefore.last() != '=') return false
        val beforeEquals = textBefore.dropLast(1)
        val exprStart = beforeEquals.indexOfLast { it !in "0123456789+-*/. " }
        val expression = beforeEquals.substring(exprStart + 1)
        val result = Calculator.evaluate(expression) ?: return false
        val formatted = Calculator.formatResult(result)
        val display = "$expression=$formatted"
        activeCorrection = ActiveCorrection(
            mode = CorrectionApplyMode.RETROACTIVE,
            originalWord = expression + "=",
            occupiedBefore = expression.length + 1,
            occupiedAfter = 0,
            separator = "",
        )
        suggestionCycleIndex = -1
        _uiState.update { it.copy(suggestions = listOf(display), firstSuggestionKind = SuggestionKind.CORRECTION) }
        return true
    }

    private fun onSpace() {
        if (shouldConvertDoubleSpaceToPeriod()) {
            convertPrecedingSpaceToPeriod()
            return
        }
        maybeAutocorrectBufferedWord()
        flushWordBuffer(separator = " ")
        textEditor.insertSpace()
        lastWordBoundarySeparator = " "
        lastSpaceCommitAtMs = System.currentTimeMillis()
        refreshSuggestions(checkContextualCorrection = true)
        maybeAutoCapitalize()
    }

    /** "Double tap space for period" (off by default — `AutocorrectSettings
     * .doubleTapSpaceForPeriod`): two spaces in quick succession become ". " instead, same
     * convention as most mainstream keyboards. Also covers double *swipe-right*, with zero extra
     * wiring — [onSwipeRight] already just calls [onSpace] when "swipe right for space" is
     * enabled, so both gestures share this exact same detection.
     *
     * Time-based, not a tap counter: [lastSpaceCommitAtMs] only ever gets set at the bottom of a
     * *plain* space commit, so anything else happening in between (typing a letter, deleting,
     * moving the cursor) simply never refreshes it and the window quietly expires — no explicit
     * "cancel" needed anywhere else. Also verified against the live text (the character
     * immediately before the cursor really is the space this same mechanism just committed, not
     * e.g. one the user pasted or moved the cursor back onto within the window) rather than
     * trusting elapsed time alone. */
    private fun shouldConvertDoubleSpaceToPeriod(): Boolean {
        if (!autocorrectPreferences.settings.value.doubleTapSpaceForPeriod) return false
        if (lastSpaceCommitAtMs == 0L) return false
        if (System.currentTimeMillis() - lastSpaceCommitAtMs > DOUBLE_TAP_SPACE_WINDOW_MS) return false
        return textEditor.textBeforeCursor(1) == " "
    }

    private fun convertPrecedingSpaceToPeriod() {
        textEditor.deleteCharacterBackward()
        textEditor.commitCharacter('.')
        textEditor.insertSpace()
        lastWordBoundarySeparator = " "
        lastSpaceCommitAtMs = 0L
        refreshSuggestions(checkContextualCorrection = true)
        maybeAutoCapitalize()
    }

    /** Same detection as [shouldConvertDoubleSpaceToPeriod] (same timing window, same "verify the
     * live text" check), but gated on its own separate preference and triggered by swipe-down
     * instead of a second space/swipe-right — the two features are independent and either, both,
     * or neither can be on. */
    private fun shouldConvertSpaceToCommaOnSwipeDown(): Boolean {
        if (!autocorrectPreferences.settings.value.doubleSpaceSwipeDownForComma) return false
        if (lastSpaceCommitAtMs == 0L) return false
        if (System.currentTimeMillis() - lastSpaceCommitAtMs > DOUBLE_TAP_SPACE_WINDOW_MS) return false
        return textEditor.textBeforeCursor(1) == " "
    }

    /** Deletes the space just committed and replaces it with a comma glued directly to the word
     * before it, then a fresh space — the comma sits right beside the text ("word,"), never
     * separated from it by whitespace. */
    private fun convertPrecedingSpaceToComma() {
        textEditor.deleteCharacterBackward()
        textEditor.commitCharacter(',')
        textEditor.insertSpace()
        lastWordBoundarySeparator = " "
        lastSpaceCommitAtMs = 0L
        refreshSuggestions(checkContextualCorrection = true)
        maybeAutoCapitalize()
    }

    /** Off by default (per user request) — only capitalizes the very start of a field or right
     * after sentence-ending punctuation, never mid-sentence. Caps lock always wins over this.
     * Skipped entirely under a TRANSLITERATED input method (Nepali Romanized): shift there means
     * "type the retroflex/long-vowel variant" (see NepaliTransliterator's doc), not real
     * capitalization — auto-shifting a word's first letter would silently misspell it (e.g.
     * "dhanyabad" auto-capitalized to "Dhanyabad" reads as retroflex ढ, not dental ध, producing
     * ढन्यवाद instead of the correct धन्यवाद). Devanagari has no case to capitalize regardless. */
    private fun maybeAutoCapitalize() {
        if (!autocorrectPreferences.settings.value.autoCapitalizeEnabled) return
        if (activeInputMethod().kind == InputMethodKind.TRANSLITERATED) return
        if (_uiState.value.capsLockOn) return
        val before = textEditor.textBeforeCursor(3).trimEnd { it == ' ' }
        val shouldCapitalize = before.isEmpty() || before.last() in ".!?"
        if (shouldCapitalize) _uiState.update { it.copy(shiftOn = true) }
    }

    private fun onEnter() {
        maybeAutocorrectBufferedWord()
        val action = _uiState.value.enterAction
        val willInsertNewline = action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        // A real editor action (Go/Search/Send/...) doesn't insert anything of its own after the
        // word — nothing to record as a trailing separator for undo in that case.
        flushWordBuffer(separator = if (willInsertNewline) "\n" else "")
        if (willInsertNewline) {
            textEditor.insertNewline()
            lastWordBoundarySeparator = "\n"
            refreshSuggestions(checkContextualCorrection = true)
            maybeAutoCapitalize()
        } else {
            // A real editor action (Go/Search/Send/...) commonly submits or navigates away —
            // the field this word lived in may no longer even be there, so there's nothing
            // sensible to retroactively correct. Not calling refreshSuggestions here at all
            // (rather than calling it without the contextual check) also avoids a pointless
            // query racing whatever the action itself triggers.
            textEditor.sendEditorAction(action)
        }
    }

    /** Checked before every word-boundary commit (space/punctuation/enter). Replaces the
     * just-typed word in place if [AutocorrectIndex] is confident it's a typo of a much more
     * common word, preserving the original capitalization pattern. No-ops (and clears any stale
     * undo record) otherwise. Deliberately uses the narrower, conservative [AutocorrectIndex.correct]
     * (not [AutocorrectIndex.alternatives]) — this is the *silent* auto-apply path, so it should
     * only ever fire for something that plainly isn't a real word, never a "well" -> "we'll" style
     * variant of something already valid; those stay opt-in, offered on the suggestion strip. */
    private fun maybeAutocorrectBufferedWord() {
        val typed = currentWordBuffer.toString()
        // The user just backspaced this exact word back to what they actually typed — respect
        // that as a rejection instead of immediately re-correcting it right back on the very next
        // word boundary (Gboard/iOS convention: reverting once "sticks" for that word).
        if (typed.isNotEmpty() && typed == revertedWord) {
            revertedWord = null
            lastAutocorrect = null
            return
        }
        revertedWord = null
        lastAutocorrect = null
        if (!autocorrectPreferences.settings.value.autocorrectEnabled) return
        if (typed.isEmpty()) return
        val correctedLower = autocorrectIndex.correct(typed) ?: return
        val corrected = matchCase(typed, correctedLower)
        if (corrected == typed) return
        commitCorrection(corrected)
        lastAutocorrect = AutocorrectRecord(original = typed, corrected = corrected)
    }

    private fun matchCase(typed: String, correctedLower: String): String = when {
        typed.all { it.isUpperCase() } -> correctedLower.uppercase()
        typed.first().isUpperCase() -> correctedLower.replaceFirstChar { it.uppercase() }
        else -> correctedLower
    }

    private fun onDeleteCharacter() {
        // A non-empty selection (e.g. after "Select all") always takes priority over the normal
        // one-character-back deletion — deleteSurroundingText is relative to the cursor and
        // doesn't know about an active selection at all, so without this check, backspacing with
        // everything selected would just nibble one character next to the cursor instead of
        // clearing the selection the way every other text editor on the platform does.
        if (textEditor.hasSelection()) {
            lastAutocorrect = null
            revertedWord = null
            suggestionCycleIndex = -1
            deleteCoalesceActive = false
            currentWordBuffer.clear()
            romanizedRawBuffer.clear()
            romanizedCommittedLength = 0
            textEditor.deleteSelection()
            refreshSuggestions()
            return
        }
        val record = lastAutocorrect
        if (record != null && currentWordBuffer.isEmpty()) {
            // First backspace immediately after an autocorrect swap (the buffer was cleared by
            // the boundary commit that triggered it) reverts to what was actually typed — same
            // convention as Gboard/iOS — instead of just deleting one character of the "fixed"
            // word. +1 accounts for the single separator char (space/punctuation/newline) always
            // committed right after the corrected word by whichever boundary triggered this.
            lastAutocorrect = null
            revertedWord = record.original
            deleteCoalesceActive = false
            repeat(record.corrected.length + 1) { textEditor.deleteCharacterBackward() }
            record.original.forEach { textEditor.commitCharacter(it) }
            currentWordBuffer.clear()
            currentWordBuffer.append(record.original)
            refreshSuggestions()
            return
        }
        lastAutocorrect = null
        revertedWord = null
        suggestionCycleIndex = -1
        if (romanizedRawBuffer.isNotEmpty()) {
            // Pops one *raw Latin keystroke*, not one committed Devanagari character — removing a
            // single Latin letter can change multiple trailing codepoints of the rendering (e.g.
            // "sh" -> "s" changes श back to स), so this re-renders the whole remaining buffer and
            // swaps it in, the same delete-then-recommit idiom onRomanizedLetterKey uses to type
            // forward.
            romanizedRawBuffer.deleteCharAt(romanizedRawBuffer.length - 1)
            val rendered = NepaliTransliterator.transliterate(romanizedRawBuffer.toString())
            repeat(romanizedCommittedLength) { textEditor.deleteCharacterBackward() }
            textEditor.insertText(rendered)
            romanizedCommittedLength = rendered.length
            currentWordBuffer.clear()
            currentWordBuffer.append(rendered)
            deleteCoalesceActive = false
            refreshSuggestionsAfterDeletion()
            return
        }
        if (currentWordBuffer.isNotEmpty()) {
            // Backspacing within a word still open for editing — absorbed into whichever
            // Inserted undo step that word eventually becomes on its own word boundary; not
            // independently undoable mid-word, same as before.
            currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
            deleteCoalesceActive = false
            textEditor.deleteCharacterBackward()
            refreshSuggestionsAfterDeletion()
            return
        }
        // Buffer already empty — this backspace removes a character from already-committed
        // text (the single most common backspace usage: erasing the end of a finished
        // sentence), previously untracked by undo entirely. Read the character before deleting
        // it, then record (and coalesce with any immediately preceding run of the same kind —
        // see [deleteCoalesceActive]) so Ctrl+Z-style undo can restore it.
        val deletedChar = textEditor.textBeforeCursor(1).lastOrNull()
        textEditor.deleteCharacterBackward()
        if (deletedChar != null) recordPlainCharDelete(deletedChar)
        refreshSuggestionsAfterDeletion()
    }

    /** Merges a run of consecutive plain single-character backspaces into already-committed text (the
     * final branch of [onDeleteCharacter]) into one [UndoEvent.Deleted] undo step, rather than
     * pushing a separate one-character step per keystroke — matches how a backspace run reads as
     * "one edit" in mainstream text editors. [deleteCoalesceActive] is reset to false by every
     * other action that mutates text or moves the cursor, so the merge only continues across an
     * unbroken run of exactly this kind of backspace. */
    private fun recordPlainCharDelete(char: Char) {
        val top = undoStack.lastOrNull()
        if (deleteCoalesceActive && top is UndoEvent.Deleted) {
            undoStack[undoStack.lastIndex] = UndoEvent.Deleted(char + top.text)
            redoStack.clear()
            _uiState.update { it.copy(canUndo = true, canRedo = false) }
        } else {
            pushUndo(UndoEvent.Deleted(char.toString()))
        }
        deleteCoalesceActive = true
    }

    private fun onDeleteWord() {
        lastAutocorrect = null
        revertedWord = null
        suggestionCycleIndex = -1
        deleteCoalesceActive = false
        currentWordBuffer.clear()
        // Same selection-takes-priority rule as onDeleteCharacter() — swipe-left with everything
        // selected should clear the selection, not just delete one word next to the cursor.
        if (textEditor.hasSelection()) {
            textEditor.deleteSelection()
            refreshSuggestions()
            return
        }
        // Read before deleting — deleteWordBackward() doesn't report what it removed, and undo
        // needs the *exact* text back (including whatever whitespace deleteWordBackward's own
        // scan consumes with it — a plain wordAtCursor().word would silently drop that on undo,
        // a real bug) to retype it precisely.
        val deletedText = textEditor.wordBackwardDeletionPreview()
        textEditor.deleteWordBackward()
        if (!deletedText.isNullOrEmpty()) pushUndo(UndoEvent.Deleted(deletedText))
        refreshSuggestionsAfterDeletion()
    }

    /** Deleting a word/character can leave the cursor sitting right after a *previously*
     * committed word instead of at an empty/ordinary typing position — e.g. "okay i wont do "
     * with "do" deleted leaves "okay i wont " with nothing in [currentWordBuffer]. Plain
     * [refreshSuggestions] only reacts to [lastCommittedWord] (typing-order bookkeeping that a
     * deletion doesn't update) or the still-open buffer, so it would otherwise show nothing for
     * "wont" here — this instead derives the word actually sitting before the cursor straight
     * from the live text (via [TextEditor.wordBeforeCursor], same "don't trust typing-order
     * bookkeeping" approach [onCursorMoved] uses for the analogous tap-into-a-word case) and
     * offers alternatives for it in [CorrectionApplyMode.RETROACTIVE] mode, so swipe-down/up
     * cycling works immediately after a delete, not just after normal typing.
     *
     * Internal, not private: also called from [OmakeyInputMethodService]'s extension-facing
     * `TextEditorFacade.deleteBackward` — deleting text via an extension (e.g. the emoji panel's
     * own backspace button) bypasses [onDeleteCharacter] entirely, since it goes straight through
     * the raw [TextEditor] rather than a key tap, so nothing was ever refreshing/clearing the
     * suggestion strip for that path (real bug, fixed: delete all text while the emoji panel is
     * open and the previous word's suggestions kept showing, stale, with nothing left to suggest
     * for). */
    internal fun refreshSuggestionsAfterDeletion() {
        // See tryShowCalculatorResult()'s own doc — backspacing away just the applied result of
        // "12+7=19" leaves the cursor sitting right after "12+7=" again, which should show the
        // calculator suggestion again rather than falling through to plain word logic.
        if (tryShowCalculatorResult()) return
        if (currentWordBuffer.isNotEmpty()) {
            refreshSuggestions()
            return
        }
        val wordBeforeCursor = textEditor.wordBeforeCursor()
        if (wordBeforeCursor == null) {
            refreshSuggestions()
            return
        }
        // Set directly (not via refreshSuggestions(), which would immediately overwrite it based
        // on currentWordBuffer/lastCommittedWord — neither necessarily matches wordBeforeCursor
        // here, since this whole branch exists precisely for the case where a deletion left the
        // cursor sitting after a word neither of those is tracking).
        updateEmojiSuggestions(wordBeforeCursor.word)
        lastCommittedWord = wordBeforeCursor.word.lowercase()
        suggestionCycleIndex = -1
        // wordAlternatives' Damerau-Levenshtein scan is too expensive to run inline here — this
        // runs on every single backspace once the buffer is empty (i.e. every delete into
        // already-committed text), and on slower/older devices that was enough to stall the UI
        // thread mid-backspace. Offloaded the same way refreshSuggestions() already does.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val alternatives = withContext(Dispatchers.Default) { wordAlternatives(wordBeforeCursor.word) }
            if (alternatives.isEmpty()) {
                refreshPlainPrediction()
                return@launch
            }
            activeCorrection = ActiveCorrection(
                mode = CorrectionApplyMode.RETROACTIVE,
                originalWord = wordBeforeCursor.word,
                occupiedBefore = wordBeforeCursor.word.length,
                occupiedAfter = 0,
                separator = wordBeforeCursor.separator,
            )
            _uiState.update { it.copy(suggestions = alternatives, firstSuggestionKind = SuggestionKind.CORRECTION) }
        }
    }

    private fun pushUndo(event: UndoEvent) {
        undoStack.addLast(event)
        if (undoStack.size > UNDO_STACK_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        // Every caller except recordPlainCharDelete's "start a new run" branch wants this off;
        // that one immediately sets it back to true right after calling pushUndo, so it's safe
        // to unconditionally clear it here rather than repeat this at every call site.
        deleteCoalesceActive = false
        _uiState.update { it.copy(canUndo = true, canRedo = false) }
    }

    /** Reverses the most recent tracked text edit — see [UndoEvent]'s doc for exactly what's
     * covered. Deletes/retypes the *exact* recorded text ([UndoEvent.Inserted.text]/
     * [UndoEvent.Deleted.text] already include the real separator/whitespace involved), so
     * undo/redo round-trip losslessly instead of the old scheme's hardcoded-space assumption. */
    fun undo() {
        val event = undoStack.removeLastOrNull() ?: return
        when (event) {
            is UndoEvent.Inserted -> repeat(event.text.length) { textEditor.deleteCharacterBackward() }
            is UndoEvent.Deleted -> textEditor.insertText(event.text)
        }
        redoStack.addLast(event)
        if (redoStack.size > UNDO_STACK_LIMIT) redoStack.removeFirst()
        currentWordBuffer.clear()
        suggestionCycleIndex = -1
        activeCorrection = null
        lastAutocorrect = null
        revertedWord = null
        deleteCoalesceActive = false
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = true) }
        refreshSuggestionsAfterDeletion()
    }

    fun redo() {
        val event = redoStack.removeLastOrNull() ?: return
        when (event) {
            is UndoEvent.Inserted -> textEditor.insertText(event.text)
            is UndoEvent.Deleted -> repeat(event.text.length) { textEditor.deleteCharacterBackward() }
        }
        undoStack.addLast(event)
        currentWordBuffer.clear()
        suggestionCycleIndex = -1
        activeCorrection = null
        lastAutocorrect = null
        revertedWord = null
        deleteCoalesceActive = false
        _uiState.update { it.copy(canUndo = true, canRedo = redoStack.isNotEmpty()) }
        refreshSuggestionsAfterDeletion()
    }

    // Deliberately does NOT call autocorrectIndex.learn()/predictionEngine.recordAcceptedWord()
    // for ordinary typing — only the explicit swipe-up "save word" gesture
    // (revertAndMaybeSave) teaches the dictionary a new word. Every word boundary used to
    // silently learn whatever was just typed, including typos that weren't caught (e.g. because
    // autocorrect was off, or the typo wasn't a recognized one-edit neighbor of anything) — those
    // got permanently marked "known" the moment they were finished, immune to correction forever
    // after. Bigram/frequency data from the seeded corpus still drives prediction and completion;
    // it just no longer grows from casual typing.
    private fun flushWordBuffer(separator: String = "") {
        suggestionCycleIndex = -1
        val word = currentWordBuffer.toString()
        if (word.isNotEmpty()) {
            previousToLastCommittedWord = lastCommittedWord
            lastCommittedWord = word.lowercase()
            currentWordBuffer.clear()
            pushUndo(UndoEvent.Inserted(word + separator))
        }
    }

    /** A tap while caps lock is engaged turns it off entirely (back to lowercase) — standard
     * mobile keyboard convention — rather than just toggling the one-shot [KeyboardUiState.shiftOn]
     * underneath it, which would leave caps lock's own flag stuck on. See [enableCapsLock] for how
     * caps lock gets turned on in the first place (long-press, not a tap). */
    private fun toggleShift() {
        _uiState.update {
            if (it.capsLockOn) it.copy(shiftOn = false, capsLockOn = false) else it.copy(shiftOn = !it.shiftOn)
        }
    }

    /** Long-press on shift (see the gesture handling in `KeyGrid`) — capitalizes every letter
     * until shift is tapped again, unlike a plain tap's one-shot capitalize-next-letter. */
    fun enableCapsLock() {
        _uiState.update { it.copy(shiftOn = true, capsLockOn = true) }
    }

    private fun switchLayout(layout: KeyboardLayout) {
        _uiState.update { it.copy(layout = layout) }
    }

    /** Opens the emoji panel by default (matches the 😊 key's icon); tapping again closes it.
     * Once open, the user can switch to other registered extensions via [selectExtension]. */
    private fun toggleExtensionPanel() {
        val preferredId = extensionRegistry.getById(PREFERRED_EXTENSION_ID)?.id
            ?: extensionRegistry.all().firstOrNull()?.id
            ?: return
        _uiState.update {
            it.copy(activeExtensionId = if (it.activeExtensionId != null) null else preferredId)
        }
    }

    fun selectExtension(id: String) {
        _uiState.update { it.copy(activeExtensionId = id) }
    }

    /** Cheap, synchronous static-table lookup (see [WordEmojiSuggestions]) — unlike word
     * suggestions/predictions, never worth a background [refreshJob] of its own. */
    private fun updateEmojiSuggestions(word: String?) {
        val emoji = word?.let(WordEmojiSuggestions::suggest).orEmpty()
        _uiState.update { it.copy(emojiSuggestions = emoji) }
    }

    /** Tapping an emoji-suggestion chip (see [KeyboardUiState.emojiSuggestions]) just inserts it
     * next to whatever's already there — entirely independent of [activeCorrection]/word-cycling
     * state, since the emoji isn't replacing or completing the word, only riding along with it. */
    fun onEmojiSuggestionAccepted(emoji: String) {
        textEditor.insertText(emoji)
        _uiState.update { it.copy(emojiSuggestions = emptyList()) }
    }

    /** [checkContextualCorrection] is true right after any word-boundary commit that leaves a
     * definite, known separator behind the finished word — space, punctuation, or Enter inserting
     * a literal newline (see [onSpace]/[commitTypedText]/[onEnter], and [lastWordBoundarySeparator]
     * for why an editor action like "Go"/"Send" doesn't qualify).
     *
     * Three distinct outcomes, in priority order:
     * 1. **Still typing a word** ([currentWordBuffer] non-empty): alternatives for *that* word
     *    (see [wordAlternatives]) merged with prefix completions, `activeCorrection` = LIVE_BUFFER.
     * 2. **Just finished a word** (buffer empty, [checkContextualCorrection] true): alternatives
     *    for [lastCommittedWord], re-ranked by what the word *before* it suggests was actually
     *    meant (see [reorderByContext]) when there's ambiguity — e.g. "thus" only outranks "this"
     *    here if the preceding word's history actually favors "this". `activeCorrection` =
     *    RETROACTIVE if any alternatives exist.
     * 3. **Neither** (nothing to vary): falls back to plain next-word prediction, gated by the
     *    separate next-word-prediction toggle; no `activeCorrection` — accepting one of these
     *    types a fresh word rather than replacing anything. */
    private fun refreshSuggestions(checkContextualCorrection: Boolean = false) {
        // Cancels any in-flight query from the previous keystroke first — without this, a slow
        // query for an earlier (now-stale) prefix can resolve after a faster later one and
        // overwrite the suggestion strip with outdated results.
        refreshJob?.cancel()
        val prefix = currentWordBuffer.toString()
        updateEmojiSuggestions(prefix.ifEmpty { lastCommittedWord.takeIf { checkContextualCorrection } })

        if (prefix.isNotEmpty()) {
            // activeCorrection is set synchronously (cheap — just bookkeeping) so cycling/revert
            // logic has it available immediately; the expensive Damerau-Levenshtein scan itself
            // (wordAlternatives) runs off the main thread below so it never blocks the next tap.
            activeCorrection = ActiveCorrection(
                mode = CorrectionApplyMode.LIVE_BUFFER,
                originalWord = prefix,
                occupiedBefore = prefix.length,
                occupiedAfter = 0,
                separator = "",
            )
            refreshJob = scope.launch {
                val alternatives = withContext(Dispatchers.Default) { wordAlternatives(prefix) }
                val predicted = predictionEngine.suggestNext(lastCommittedWord, prefix, limit = SUGGESTION_LIMIT)
                val suggestions = (alternatives + predicted.filterNot { p -> alternatives.any { it.equals(p, ignoreCase = true) } })
                    .take(SUGGESTION_LIMIT)
                _uiState.update {
                    it.copy(
                        suggestions = suggestions,
                        firstSuggestionKind = if (alternatives.isNotEmpty()) SuggestionKind.CORRECTION else SuggestionKind.PLAIN,
                    )
                }
            }
            return
        }

        val target = lastCommittedWord.takeIf { checkContextualCorrection }
        if (target != null) {
            val context = previousToLastCommittedWord
            refreshJob = scope.launch {
                val rawAlternatives = withContext(Dispatchers.Default) { wordAlternatives(target) }
                if (rawAlternatives.isNotEmpty()) {
                    activeCorrection = ActiveCorrection(
                        mode = CorrectionApplyMode.RETROACTIVE,
                        originalWord = target,
                        occupiedBefore = target.length,
                        occupiedAfter = 0,
                        separator = lastWordBoundarySeparator,
                    )
                    val ranked = reorderByContext(context, rawAlternatives)
                    _uiState.update { it.copy(suggestions = ranked, firstSuggestionKind = SuggestionKind.CORRECTION) }
                } else {
                    refreshPlainPrediction()
                }
            }
            return
        }

        refreshPlainPrediction()
    }

    /** Nothing to correct/vary — plain next-word prediction if enabled, no active correction
     * (accepting one of these types a fresh word, doesn't replace anything). */
    private fun refreshPlainPrediction() {
        activeCorrection = null
        if (!predictionPreferences.settings.value.nextWordPredictionEnabled) {
            _uiState.update { it.copy(suggestions = emptyList(), firstSuggestionKind = SuggestionKind.PLAIN) }
            return
        }
        refreshJob = scope.launch {
            val predicted = predictionEngine.suggestNext(lastCommittedWord, "", limit = SUGGESTION_LIMIT)
            _uiState.update { it.copy(suggestions = predicted, firstSuggestionKind = SuggestionKind.PLAIN) }
        }
    }

    /** [AutocorrectIndex.alternatives] for [word], case-matched against it — except a curated
     * contraction result (already correctly cased, e.g. "I'm") or a two-word split (left as
     * lowercase, reads fine either way), neither of which should have [matchCase]'s single-word
     * casing rules applied on top. */
    private fun wordAlternatives(word: String): List<String> {
        val contraction = autocorrectIndex.contractionFor(word)
        return autocorrectIndex.alternatives(word, SUGGESTION_LIMIT).map { alt ->
            when {
                alt == contraction -> alt
                alt.contains(' ') -> alt
                else -> matchCase(word, alt)
            }
        }
    }

    /** Re-ranks [candidates] (real-word alternatives to a word that's itself already valid — the
     * "real-word error" case, e.g. "thus" vs. "this") by which one actually fits [context] (the
     * word immediately before it), when that data clearly favors one over the raw frequency-based
     * order [wordAlternatives] already applied. Never promotes a candidate on weak/no evidence —
     * requires *some* real usage history for the pairing, not just the absence of a tie — since
     * this is about picking between multiple already-valid words, a lower-confidence judgment call
     * than fixing an outright typo. */
    private suspend fun reorderByContext(context: String?, candidates: List<String>): List<String> {
        if (context == null || candidates.size <= 1) return candidates
        val ranked = candidates.map { it to predictionEngine.bigramRank(context, it.lowercase()) }
        if (ranked.all { it.second == 0 }) return candidates
        return ranked.sortedByDescending { it.second }.map { it.first }
    }

    private companion object {
        const val PREFERRED_EXTENSION_ID = "builtin.emoji"
        const val SUGGESTION_LIMIT = 6
        const val UNDO_STACK_LIMIT = 50
        // Matches the ~500ms window most mainstream keyboards use for double-tap-space-for-period.
        const val DOUBLE_TAP_SPACE_WINDOW_MS = 500L
    }
}
