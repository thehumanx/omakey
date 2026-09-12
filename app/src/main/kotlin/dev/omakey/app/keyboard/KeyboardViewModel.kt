package dev.omakey.app.keyboard

import android.view.inputmethod.EditorInfo
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.emoji.EmojiSkinTone
import dev.omakey.core.emoji.WordEmojiSuggestions
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.input.TextEdit
import dev.omakey.core.input.TextEditor
import dev.omakey.core.input.UndoHistory
import dev.omakey.core.input.isSensitiveField
import dev.omakey.core.predict.matchCase
import dev.omakey.core.predict.splitCorrection
import dev.omakey.core.input.WordTracker
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.KeyboardPlacement
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.flippedOneHandedSide
import dev.omakey.core.layout.nextOneHanded
import dev.omakey.core.layout.toggledWith
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.IncognitoPreferences
import dev.omakey.core.predict.Calculator
import dev.omakey.core.predict.PredictionEngine
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.predict.SuggestionComposer
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


/**
 * Owns the current typing session's state: active layout, shift state, suggestions, and which
 * extension panel (if any) is open. Routes all committed text through TextEditor and all
 * gesture-derived actions through the same handler as tap-derived ones, so gestures are strictly
 * additive to (not a separate path from) the tap-based key actions from M1.
 */
class KeyboardViewModel(
    private val textEditor: TextEditor,
    private val predictionEngine: PredictionEngine,
    /** Emits once the language model is mapped. Separate from [predictionEngine] itself because the
     * engine handed in during a cold start is a [dev.omakey.core.predict.DeferredPredictionEngine]
     * that answers every call with nothing until then. */
    private val predictionReady: StateFlow<Boolean> = MutableStateFlow(true),
    private val autocorrectIndex: AutocorrectIndex,
    private val autocorrectPreferences: AutocorrectPreferences,
    private val predictionPreferences: PredictionPreferences,
    private val incognitoPreferences: IncognitoPreferences,
    val extensionRegistry: ExtensionRegistry,
    private val themeRepository: ThemeRepository,
    private val layoutPreferences: LayoutPreferences,
    fontPreferences: FontPreferences,
    gesturePreferences: GesturePreferences,
    private val topStripTabPreferences: TopStripTabPreferences,
    private val scope: CoroutineScope,
    // Called with the copied/cut text right before the actual system copy/cut fires, so the host
    // service can record it into clipboard history itself and suppress its own listener's
    // primaryClip read for that one change — see OmakeyInputMethodService's clipboardListener for
    // why avoiding that read is what actually avoids the second "read your clipboard" toast.
    private val onClipboardCopy: (String) -> Unit = {},
    // The clipboard's current text, or null if it holds no text (or can't be read). Injected rather
    // than read through a ClipboardManager here so this class stays free of Android system
    // services — and so tests can paste without one. See [onPaste] for why omakey reads the
    // clipboard on this path at all, and why doing so is toast-free.
    private val clipboardText: () -> CharSequence? = { null },
    // The user's chosen emoji skin tone, read per use rather than captured — it can change from
    // Settings while the keyboard is open.
    private val emojiSkinTone: () -> EmojiSkinTone = { EmojiSkinTone.DEFAULT },
) {
    private val _uiState = MutableStateFlow(
        KeyboardUiState(
            theme = themeRepository.currentTheme.value,
            useSystemAccent = themeRepository.useSystemAccent.value,
            layoutMode = themeRepository.layoutMode.value,
            layoutSettings = layoutPreferences.settings.value,
            fontId = fontPreferences.fontId.value,
            gestureSettings = gesturePreferences.settings.value,
            topStripTab = topStripTabPreferences.tab.value,
        ),
    )
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    /**
     * The word being typed and the two finished before it — see [WordTracker], which owns the
     * three-field invariant these used to maintain by hand at eight separate call sites.
     */
    private val words = WordTracker()

    /** Owns candidate selection; this class owns *when* to ask and what to do with the answer.
     * Constructed here rather than injected because both its dependencies are already constructor
     * parameters — it is a regrouping of what this class already had, not a new collaborator. */
    private val suggestionComposer = SuggestionComposer(autocorrectIndex, predictionEngine, SUGGESTION_LIMIT)

    /** When the most recent space was actually committed — 0 means "none yet this session, or
     * already consumed by a double-tap conversion." Powers [onSpace]'s double-tap-space-for-
     * period detection (`AutocorrectSettings.doubleTapSpaceForPeriod`); see that function's own
     * doc for why this is time-based rather than counting taps. */
    private var lastSpaceCommitAtMs: Long = 0L

    /** True once a symbol/digit has actually been typed while on `Symbols1`/`Symbols2` (set in
     * [commitTypedChar]) — the *next* [onSpace] then switches back to the letters layout after
     * inserting the space, matching how the space bar behaves on mainstream keyboards after
     * punctuation. Reset on entering symbols mode fresh or leaving it (see [onKeyTap]'s
     * `SYMBOLS`/`LETTERS` branches) so a plain page-switch with nothing typed doesn't trigger it. */
    private var symbolTypedInSymbolsMode = false

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
     * Enter), a word being deleted (swipe-left), plain character-by-character backspacing through
     * already-committed text, and every non-typing edit that moves text in one gesture (paste, cut,
     * deleting a selection, an emoji chip, an extension insertion).
     *
     * Autocorrect/suggestion corrections are deliberately *not* here — they already have dedicated,
     * more precise revert gestures (backspace-reverts-the-swap, swipe up/down cycling), and folding
     * them into this generic stack would fight with that machinery rather than complement it.
     * Android's `InputConnection` has no standardized cross-app undo API
     * (`performContextMenuAction(android.R.id.undo)` isn't reliably implemented by host apps), so
     * this is necessarily omakey's own app-level history, not a passthrough to the host app's.
     *
     * The sequencing rules — what coalesces, what breaks a run, what invalidates redo — live in
     * [UndoHistory] in `core`, which has no Android dependency and is unit-tested directly. This
     * class only decides *what counts as one edit* and applies the result via
     * [TextEditor.replaceBackward]. That split is what made the rules testable at all:
     * `KeyboardViewModel` needs a `Context` for every one of its preference dependencies and cannot
     * be constructed in a plain JVM test.
     *
     * **The bug this replaced**: paste wasn't recorded at all — it went straight to
     * `performContextMenuAction(android.R.id.paste)`, which the host app services, so omakey never
     * learned what landed in the field. Undo then popped whatever *typed* step happened to be on
     * top and deleted that many characters, chewing backwards through the pasted text a fragment at
     * a time and leaving the stack describing text that no longer existed. Cut, delete-selection,
     * emoji chips and extension insertions were untracked for the same reason.
     *
     * Every recorded edit carries the *exact* text on both sides, so nothing is inferred: a
     * finished word carries whatever real separator followed it (space, punctuation, or newline;
     * assuming a plain space used to silently corrupt it, a real bug), and a word deletion carries
     * whatever whitespace [TextEditor.deleteWordBackward] actually consumed with it. */
    private val undoHistory = UndoHistory()


    /** How to apply whichever `suggestions[index]` the user swipes/taps to, for a word currently
     * "in focus" for the strip — unifying the three different ways a word gets there so cycling
     * (repeated swipe up/down through the *same* frozen list) works identically regardless of
     * which one. [occupiedBefore]/[occupiedAfter] track how many characters *currently* sit where
     * the word is (updated after every cycle step, since each candidate can be a different
     * length) — not the original word's length, except before the first step. */
    private enum class CorrectionApplyMode {
        /** Word is still being actively typed (the [WordTracker] buffer is it) — delete the buffer,
         * retype, leave it open for further editing (matches ordinary completion-cycling). */
        LIVE_BUFFER,

        /** Word was just finished (space/punctuation/Enter already committed [WordTracker.boundarySeparator]
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
        incognitoPreferences.incognito
            .onEach { enabled -> _uiState.update { it.copy(incognito = enabled) } }
            .launchIn(scope)
        predictionReady
            .onEach { ready ->
                _uiState.update { it.copy(suggestionsLoading = !ready) }
                // The strip was built from an engine that could not answer yet, so whatever is on
                // screen right now is the degraded result. Re-deriving it is what turns the
                // placeholder into real suggestions for the word already being typed, rather than
                // leaving the user to type another character before anything appears.
                if (ready) refreshSuggestions()
            }
            .launchIn(scope)
    }

    /** Manual incognito toggle, from the keyboard's own toolbar. Deliberately session state rather
     * than a saved preference — see [IncognitoPreferences]: someone who turns it on to type one
     * password should not silently lose personalisation forever afterwards. */
    fun toggleIncognito() {
        incognitoPreferences.setIncognito(!incognitoPreferences.incognito.value)
    }

    val extensionHost = object : ExtensionHost {
        /** Whatever an extension inserts is one undo step, whatever its length — tapping an entry
         * in the clipboard-history panel is the other way a user "pastes a paragraph", and it was
         * untracked in exactly the same way [onPaste] was. Committed as one batch rather than a
         * character at a time, so the host app sees one edit — and so an emoji panel selection
         * arrives as a whole code point instead of two `commitText` calls each carrying one half of
         * a surrogate pair, which host apps happen to reassemble but are under no obligation to. */
        override fun insertText(text: String) {
            if (text.isEmpty()) return
            textEditor.insertText(text)
            pushUndo(TextEdit(inserted = text))
            resetTypingState()
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
        // Engaged before anything else, so no word from this field can be learned even if the very
        // first keystroke arrives immediately. This is the case that actually matters: a user
        // typing a password or a recovery phrase will never think to reach for a toggle, and words
        // captured from one would sit in the dictionary indefinitely.
        incognitoPreferences.onFieldChanged(isSensitiveField(info))
        val enterAction = when {
            info == null -> EditorInfo.IME_ACTION_NONE
            (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 -> EditorInfo.IME_ACTION_NONE
            else -> info.imeOptions and EditorInfo.IME_MASK_ACTION
        }
        _uiState.update {
            it.copy(
                layout = KeyboardLocale.Default.letterLayout,
                shiftOn = autocorrectPreferences.settings.value.autoCapitalizeEnabled && textEditor.textBeforeCursor(1).isEmpty(),
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
                quickAccessOpen = false,
                resizing = false,
            )
        }
        words.clear()
        symbolTypedInSymbolsMode = false
        suggestionCycleIndex = -1
        lastAutocorrect = null
        revertedWord = null
        activeCorrection = null
        // A new field is a new editing context — undo history from whatever was focused before
        // isn't meaningful here, same lifecycle as every other per-field piece of state above.
        undoHistory.clear()
        // A word staged in the field being left never gets its confirming next word, and carrying
        // it into an unrelated field would learn it on the strength of typing that has nothing to
        // do with it.
        discardPendingLearn()
    }

    // --- placement (floating / one-handed / resize) ----------------------------------------------

    /** Opens or closes the quick-access panel. Closes any extension panel on the way in — both use
     * the key-grid slot, so they cannot both be showing. */
    fun toggleQuickAccess() {
        _uiState.update {
            val opening = !it.quickAccessOpen
            it.copy(
                quickAccessOpen = opening,
                activeExtensionId = if (opening) null else it.activeExtensionId,
                resizing = if (opening) false else it.resizing,
            )
        }
    }

    fun closeQuickAccess() = _uiState.update { it.copy(quickAccessOpen = false) }

    /**
     * Switches placement, closing the panel so the result is immediately visible — the whole point
     * of these tiles is the change they make to the keyboard behind the panel.
     *
     * Toggling: tapping the tile for the mode you are already in returns to [KeyboardPlacement.DOCKED],
     * so every tile is its own off switch and there is no separate "back to normal" control.
     */
    fun setPlacement(placement: KeyboardPlacement) {
        layoutPreferences.setPlacement(layoutPreferences.settings.value.placement.toggledWith(placement))
        _uiState.update { it.copy(quickAccessOpen = false) }
    }

    /** One-handed from a single tile: picks the right-hand side first (the majority hand), and a
     * second tap flips sides rather than switching it off — matching the gutter's own switch-side
     * button, so the tile and the gutter don't disagree. */
    fun toggleOneHanded() {
        layoutPreferences.setPlacement(layoutPreferences.settings.value.placement.nextOneHanded())
        _uiState.update { it.copy(quickAccessOpen = false) }
    }

    fun switchOneHandedSide() {
        layoutPreferences.setPlacement(layoutPreferences.settings.value.placement.flippedOneHandedSide())
    }

    /** Persists the result of a resize/move drag. Called once on drag end, never per frame — the
     * live value lives in the UI until then (see `PlacementState`). Routed through the view model
     * so the UI layer never has to build its own [LayoutPreferences], which would register a second
     * SharedPreferences listener per composition. */
    fun commitPlacementBounds(widthDp: Int, heightDp: Int, xDp: Int, yDp: Int, bottomOffsetDp: Int) {
        when (layoutPreferences.settings.value.placement) {
            KeyboardPlacement.FLOATING -> layoutPreferences.setFloatingBounds(widthDp, heightDp, xDp, yDp)
            KeyboardPlacement.ONE_HANDED_LEFT, KeyboardPlacement.ONE_HANDED_RIGHT -> {
                layoutPreferences.setOneHandedWidthDp(widthDp)
                layoutPreferences.setKeyboardHeightDp(heightDp)
                layoutPreferences.setBottomOffsetDp(bottomOffsetDp)
            }
            // Docked and one-handed keyboards can be raised off the bottom edge as well as
            // resized — the quick-access overlay does both now, matching Settings. Floating has no
            // bottom offset: its position is already free.
            KeyboardPlacement.DOCKED -> {
                layoutPreferences.setKeyboardHeightDp(heightDp)
                layoutPreferences.setBottomOffsetDp(bottomOffsetDp)
            }
        }
    }

    fun setResizing(resizing: Boolean) =
        _uiState.update { it.copy(resizing = resizing, quickAccessOpen = false) }

    /** Cycles the four built-in presets. Custom themes are deliberately not in the cycle — there can
     * be any number of them, and a tile that might need twenty taps to get back where it started is
     * not a quick action. Picking a custom theme stays a Settings job. */
    fun cycleTheme() {
        val presets = Presets.all
        val index = presets.indexOfFirst { it.id == themeRepository.currentTheme.value.id }
        val next = presets[(index + 1).mod(presets.size)]
        themeRepository.setTheme(next)
        showBanner(next.name)
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
        val cut = textEditor.selectedText()
        cut?.let(onClipboardCopy)
        textEditor.cutSelection()
        // One step for the whole selection, however large — a cut is a single gesture, so it is a
        // single undo, the same as UID_CUT is on Windows.
        if (!cut.isNullOrEmpty()) pushUndo(TextEdit(removed = cut))
    }

    /**
     * Pastes, as **one** undo step covering the whole clip.
     *
     * Prefers committing the clip text directly over delegating to the host app's paste, for the
     * sole reason that it is the only way to know what was inserted: `performContextMenuAction`
     * hands the whole operation to the host, which reads the clipboard itself and reports nothing
     * back. That left paste entirely absent from the undo history, so undoing right after a paste
     * popped an unrelated typed step and deleted that many characters off the end of the pasted
     * text — the reported symptom of a four-word paste undoing a word at a time.
     *
     * Reading the clipboard here does **not** fire Android 12+'s "pasted from your clipboard"
     * toast: `ClipboardService.showAccessNotificationLocked` exempts the current IME, which omakey
     * is by definition at the moment the user taps its own Paste button. (That exemption is why
     * this is safe *here* specifically, and not a general licence to read the clipboard — see
     * `OmakeyInputMethodService.captureCurrentClipboardIfNew`, which runs from a background
     * listener where omakey may not be the focused IME.)
     *
     * Falls back to the host-app paste when the clip has no text to offer — an image, an empty
     * clip, a `ClipboardManager` that returns null. That path still pastes correctly; it just
     * cannot be undone, which is strictly what happened before for every paste.
     */
    fun onPaste() {
        val clip = clipboardText()?.toString()
        if (clip.isNullOrEmpty()) {
            textEditor.pasteFromClipboard()
            return
        }
        // commitText replaces the selection, so a paste over selected text is one replacement —
        // and has to be recorded as one, or undo would put the clip back without the text it
        // displaced.
        val replaced = textEditor.selectedText().orEmpty()
        textEditor.insertText(clip)
        pushUndo(TextEdit(removed = replaced, inserted = clip))
        // The pasted text is not typed text: none of the word-in-progress bookkeeping describes it.
        resetTypingState()
        refreshSuggestionsAfterDeletion()
    }

    fun onKeyTap(code: Int) {
        when (code) {
            SpecialKeyCode.SHIFT -> toggleShift()
            SpecialKeyCode.BACKSPACE -> onDeleteCharacter()
            SpecialKeyCode.SPACE -> onSpace()
            SpecialKeyCode.ENTER -> onEnter()
            SpecialKeyCode.SYMBOLS -> {
                // Entering symbols mode fresh (from letters) starts a new "haven't typed a
                // symbol yet this visit" session for symbolTypedInSymbolsMode; toggling between
                // the two symbols pages is still the same visit, so the flag survives that.
                if (_uiState.value.layout.id !in SYMBOLS_LAYOUT_IDS) symbolTypedInSymbolsMode = false
                switchLayout(
                    when (_uiState.value.layout.id) {
                        Layouts.Symbols1.id -> Layouts.Symbols2
                        Layouts.Symbols2.id -> Layouts.Symbols1
                        else -> Layouts.Symbols1
                    },
                )
            }
            SpecialKeyCode.LETTERS -> {
                symbolTypedInSymbolsMode = false
                switchLayout(KeyboardLocale.Default.letterLayout)
            }
            SpecialKeyCode.EXTENSIONS -> toggleExtensionPanel()
            else -> onCharacter(code)
        }
    }

    fun onSwipeLeft() = onDeleteWord()
    fun onSwipeRight() = onSpace()

    /** Sentence-ending/quoting punctuation, in the fixed cycle order swipe up/down rotates
     * through once one of these sits immediately left of the cursor — e.g. double-tap-space
     * commits ". ", cursor ends up right after it, and a swipe down/up should turn that "."
     * into "," / "!" / etc. rather than cycling word suggestions (which is what swipe up/down
     * normally does — see [onSwipeUp]/[onSwipeDown]). */
    private val punctuationCycle = listOf('.', ',', '!', '?', ';', ':', '\'', '"')

    /** Only fires when the cursor isn't inside a word-in-progress (the [WordTracker] buffer is empty —
     * a live word is what "cursor is inside the word" means here, since a mid-word cursor from
     * navigation is otherwise indistinguishable from "just finished typing") and one of
     * [punctuationCycle]'s characters sits immediately left of the cursor, *or* exactly one space
     * left of it (e.g. double-tap-space-for-period commits ". " and moves the cursor past the
     * space — the period itself, not the space, is what should cycle; real bug report, fixed:
     * this used to require the cursor to be touching the punctuation directly, missing the single-
     * trailing-space case that's actually the common one). Replaces just the punctuation character
     * with the next/previous entry (wrapping), leaving any trailing space untouched, and returns
     * true; returns false (no-op) otherwise so the caller falls through to its normal suggestion-
     * cycling behavior. */
    private fun tryCyclePunctuation(forward: Boolean): Boolean {
        if (words.isBufferNotEmpty) return false
        val before = textEditor.textBeforeCursor(2)
        val last = before.lastOrNull() ?: return false
        val trailingSpace = last == ' ' && before.length >= 2
        val char = if (trailingSpace) before[before.length - 2] else last
        val index = punctuationCycle.indexOf(char)
        if (index == -1) return false
        val next = punctuationCycle[(index + if (forward) 1 else -1).mod(punctuationCycle.size)]
        if (trailingSpace) {
            textEditor.deleteCharacterBackward() // the space
            textEditor.deleteCharacterBackward() // the punctuation
            textEditor.commitCharacter(next)
            textEditor.insertSpace()
        } else {
            textEditor.deleteCharacterBackward()
            textEditor.commitCharacter(next)
        }
        return true
    }

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
        undoHistory.breakCoalescing()
    }

    /** Cycles left through the frozen suggestions snapshot (does not re-query, so the candidate
     * set stays stable while cycling). At the leftmost candidate — or when there's nothing to
     * cycle at all — restores the original word and, if it's still actively being typed and not
     * already a known word, saves it to the local dictionary (see [revertAndMaybeSave]). */
    fun onSwipeUp() {
        if (tryCyclePunctuation(forward = false)) return
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
     * [tryCyclePunctuation] claims this swipe first (cursor sitting right after one of
     * [punctuationCycle]'s characters) — that's the *only* way a swipe down turns into a comma
     * now. A previous "double space + swipe down for comma" feature used to trigger a comma from
     * *any* space within the double-tap window, with no punctuation involved at all — real bug
     * report: swiping down after an ordinary "hello " (just a plain space, no period anywhere)
     * inserted a comma the user never asked for, and doing it again could corrupt the word itself
     * (see [convertPrecedingSpaceToPeriod]'s doc on the separator-bookkeeping bug the equivalent
     * comma path shared and that this removal sidesteps entirely). Removed outright rather than
     * gated further — [punctuationCycle] already covers both of that
     * feature's intended uses (double-tap-space-for-period, then swipe to cycle onward to a
     * comma; or swiping on a period typed directly) without the false-positive-on-plain-space
     * behavior. */
    fun onSwipeDown() {
        if (tryCyclePunctuation(forward = true)) return
        val suggestions = _uiState.value.suggestions
        if (suggestions.isEmpty()) return
        applySuggestion((suggestionCycleIndex + 1).coerceIn(0, suggestions.size - 1))
    }

    private fun applySuggestion(index: Int) {
        val word = _uiState.value.suggestions.getOrNull(index) ?: return
        lastAutocorrect = null
        revertedWord = null
        undoHistory.breakCoalescing()
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
        // The buffer is already empty once a trailing separator (space, punctuation) has
        // flushed it — e.g. cursor sitting right after "bibek " with nothing typed since. Falling
        // back to wordBeforeCursor() recovers "bibek" (not "bibek " — its separator is reported
        // separately and never included) instead of silently no-op'ing the swipe-up-to-save.
        val original = active?.originalWord
            ?: words.bufferedWord.ifBlank { textEditor.wordBeforeCursor()?.word.orEmpty() }
        if (original.isBlank()) return
        if (active != null) applyActiveCorrection(original)
        suggestionCycleIndex = -1
        undoHistory.breakCoalescing()
        // Either branch below decides this word's fate explicitly; the staged implicit learn would
        // only duplicate or contradict it.
        discardPendingLearn()
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
        // Accepting a suggestion for the word just committed is the user saying it was wrong.
        discardPendingLearn()
        lastAutocorrect = null
        revertedWord = null
        undoHistory.breakCoalescing()
        val active = activeCorrection
        if (active != null) {
            applyActiveCorrection(word)
            if (active.mode == CorrectionApplyMode.LIVE_BUFFER) {
                // "Done with this word" — close it out with a trailing space, same as accepting a
                // plain next-word prediction below. RETROACTIVE/CURSOR corrections are already
                // sitting in finished text; nothing more to close out for those.
                if (words.isBufferNotEmpty) {
                    textEditor.commitCharacter(' ')
                    words.commitBufferedWord()
                }
            }
            suggestionCycleIndex = -1
            refreshSuggestions()
            return
        }
        commitCorrection(word)
        textEditor.commitCharacter(' ')
        // An empty buffer records nothing, where the previous open-coded version would have stored
        // an empty string as the last committed word.
        words.commitBufferedWord()
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
                val split = splitCorrection(replacement)
                if (split == null) {
                    replacement.forEach { textEditor.commitCharacter(it) }
                    words.replaceBuffer(replacement)
                    active.occupiedBefore = replacement.length
                } else {
                    commitSplitCorrection(split)
                    activeCorrection = null
                }
            }
            CorrectionApplyMode.RETROACTIVE -> {
                repeat(active.occupiedBefore + active.separator.length) { textEditor.deleteCharacterBackward() }
                replacement.forEach { textEditor.commitCharacter(it) }
                active.separator.forEach { textEditor.commitCharacter(it) }
                words.retargetLastCommitted(replacement)
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
        repeat(words.bufferLength) { textEditor.deleteCharacterBackward() }

        val split = splitCorrection(replacement)
        if (split == null) {
            replacement.forEach { textEditor.commitCharacter(it) }
            words.replaceBuffer(replacement)
            return false
        }
        commitSplitCorrection(split)
        return true
    }

    /** Commits both halves of a [splitCorrection]: the first word is finished then and there
     * (bookkeeping only, no learning — see [flushWordBuffer]'s doc), the second becomes the new
     * live buffer. Shared by [commitCorrection] and [applyActiveCorrection], which each carried
     * their own copy of this — including the [WordTracker] assignments, the exact invariant that
     * had already been got wrong once by being written out more than once. */
    private fun commitSplitCorrection(split: Pair<String, String>) {
        val (firstWord, secondWord) = split
        firstWord.forEach { textEditor.commitCharacter(it) }
        textEditor.commitCharacter(' ')
        words.commitWord(firstWord)
        secondWord.forEach { textEditor.commitCharacter(it) }
        words.replaceBuffer(secondWord)
    }

    /** Called whenever the cursor/selection changes for a reason outside the normal typing flow —
     * a tap elsewhere in the text, arrow-key navigation, autofill, etc (see
     * `OmakeyInputMethodService.onUpdateSelection`). Independent of the [WordTracker] buffer's typing-
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
            wordAtCursor.word == words.bufferedWord
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
        val alternatives = wordAlternatives(wordAtCursor.word)
        if (alternatives.isEmpty()) {
            if (activeCorrection?.mode == CorrectionApplyMode.CURSOR) {
                activeCorrection = null
                refreshSuggestions()
            }
            return
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

    private fun onCharacter(code: Int) = commitTypedChar(Character.toChars(code)[0])

    /** Also used for accent-popup selections (long-press on a key with popupChars), which arrive
     * as a Char rather than a key code since accent variants aren't part of the base layout. */
    fun onAccentSelected(char: Char) = commitTypedChar(char)

    private fun commitTypedChar(rawChar: Char) {
        suggestionCycleIndex = -1
        undoHistory.breakCoalescing()
        var char = rawChar
        if (_uiState.value.shiftOn) char = char.uppercaseChar()
        // Punctuation typed directly after a word (no space) is a word boundary too — correct
        // before committing the punctuation itself, so it lands after the fixed word.
        if (!char.isLetter()) {
            maybeAutocorrectBufferedWord()
        }
        textEditor.commitCharacter(char)
        if (_uiState.value.layout.id in SYMBOLS_LAYOUT_IDS) symbolTypedInSymbolsMode = true
        if (char.isLetter()) {
            words.appendToBuffer(char)
            refreshSuggestions()
        } else {
            flushWordBuffer(separator = char.toString())
            words.boundarySeparator = char.toString()
            // Real bug, fixed: this used to pass checkContextualCorrection = true for *every*
            // non-letter character, including digits and arbitrary symbols-page characters (@, #,
            // $, ...) that never actually end a word the way sentence punctuation does. Since
            // words is empty for these (nothing was being typed), refreshSuggestions'
            // "just finished a word" branch fired off words.lastCommittedCased instead — the last
            // word actually finished with a *real* separator, which for a fresh digit run could be
            // from several words ago (typing digits never itself updates words.lastCommitted). This
            // made the suggestion strip appear "stuck" on whatever word was last genuinely
            // committed, reappearing every time the user typed a digit/symbol with no live word
            // buffered. Only [punctuationCycle]'s actual sentence-ending/quoting characters (also
            // reused by swipe up/down's own punctuation-cycling) should re-trigger that check.
            refreshSuggestions(checkContextualCorrection = char in punctuationCycle)
            if (char == '=') tryShowCalculatorResult()
        }
        if (_uiState.value.shiftOn && !_uiState.value.capsLockOn) {
            _uiState.update { it.copy(shiftOn = false) } // one-shot shift, matches typical mobile keyboard behavior
        }
    }

    /** Inline calculator — typing "12+7=" offers the full "12+7=19" in the suggestion strip
     * (never auto-applied, same convention as every other correction), so what you tap reads as
     * a complete, self-explanatory answer rather than a bare number floating with no context.
     * Tapping it deletes the typed "12+7=" and retypes "12+7=19" in its place — same
     * [CorrectionApplyMode.RETROACTIVE] mechanism as every other correction, so on screen the net
     * effect is just the missing "19" getting appended.
     *
     * Called right after '=' itself has already been committed as ordinary punctuation (see
     * [commitTypedChar]), so [textBeforeCursor] already includes it — but also re-derived from
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
     * the [WordTracker] buffer (letters-only, never sees digits/operators in the first place), a
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
        words.boundarySeparator = " "
        lastSpaceCommitAtMs = System.currentTimeMillis()
        if (symbolTypedInSymbolsMode) {
            symbolTypedInSymbolsMode = false
            switchLayout(KeyboardLocale.Default.letterLayout)
        }
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
        // Real bug, fixed: this used to record just " " here, but the actual text sitting
        // between the word and the cursor is ". " (period *and* space, 2 characters) — the very
        // next RETROACTIVE correction (whether swiped or tapped) would then delete only
        // originalWord.length + 1 characters instead of + 2, leaving one stray leading character
        // of the old word behind every time (e.g. "hello. " cycling to "hhell. "). See
        // [ActiveCorrection.separator]'s doc — it's retyped verbatim after the replacement on
        // every cycle step, so it must match the real on-screen separator exactly.
        words.boundarySeparator = ". "
        lastSpaceCommitAtMs = 0L
        refreshSuggestions(checkContextualCorrection = true)
        maybeAutoCapitalize()
    }

    /** Off by default (per user request) — only capitalizes the very start of a field or right
     * after sentence-ending punctuation, never mid-sentence. Caps lock always wins over this. */
    private fun maybeAutoCapitalize() {
        if (!autocorrectPreferences.settings.value.autoCapitalizeEnabled) return
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
            words.boundarySeparator = "\n"
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
        val typed = words.bufferedWord
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
        val correctedLower = autocorrectIndex.correct(typed, correctionContext()) ?: return
        val corrected = matchCase(typed, correctedLower)
        if (corrected == typed) return
        commitCorrection(corrected)
        lastAutocorrect = AutocorrectRecord(original = typed, corrected = corrected)
    }

    private fun onDeleteCharacter() {
        // Any backspace at all retracts the staged word — see [pendingLearn] for why this is
        // deliberately broader than "a backspace that touches it".
        discardPendingLearn()
        // A non-empty selection (e.g. after "Select all") always takes priority over the normal
        // one-character-back deletion — deleteSurroundingText is relative to the cursor and
        // doesn't know about an active selection at all, so without this check, backspacing with
        // everything selected would just nibble one character next to the cursor instead of
        // clearing the selection the way every other text editor on the platform does.
        if (textEditor.hasSelection()) {
            forgetCorrectionMemory()
            undoHistory.breakCoalescing()
            words.clearBuffer()
            // Read before deleting, and recorded as one step for the whole selection however big
            // it is — "backspace over a selection" is one gesture, so it is one undo.
            val deleted = textEditor.selectedText()
            textEditor.deleteSelection()
            if (!deleted.isNullOrEmpty()) pushUndo(TextEdit(removed = deleted))
            refreshSuggestions()
            return
        }
        val record = lastAutocorrect
        if (record != null && words.isBufferEmpty) {
            // First backspace immediately after an autocorrect swap (the buffer was cleared by
            // the boundary commit that triggered it) reverts to what was actually typed — same
            // convention as Gboard/iOS — instead of just deleting one character of the "fixed"
            // word. +1 accounts for the single separator char (space/punctuation/newline) always
            // committed right after the corrected word by whichever boundary triggered this.
            lastAutocorrect = null
            revertedWord = record.original
            undoHistory.breakCoalescing()
            repeat(record.corrected.length + 1) { textEditor.deleteCharacterBackward() }
            record.original.forEach { textEditor.commitCharacter(it) }
            words.replaceBuffer(record.original)
            refreshSuggestions()
            return
        }
        forgetCorrectionMemory()
        if (words.isBufferNotEmpty) {
            // Backspacing within a word still open for editing — absorbed into whichever
            // Inserted undo step that word eventually becomes on its own word boundary; not
            // independently undoable mid-word, same as before.
            words.deleteLastBufferedChar()
            undoHistory.breakCoalescing()
            textEditor.deleteCharacterBackward()
            refreshSuggestionsAfterDeletion()
            return
        }
        // Buffer already empty — this backspace removes a character from already-committed
        // text (the single most common backspace usage: erasing the end of a finished
        // sentence), previously untracked by undo entirely. Read the character before deleting
        // it, then record (and coalesce with any immediately preceding run of the same kind —
        // see [UndoHistory.recordBackspace]) so Ctrl+Z-style undo can restore it.
        val deletedChar = textEditor.textBeforeCursor(1).lastOrNull()
        textEditor.deleteCharacterBackward()
        if (deletedChar != null) recordPlainCharDelete(deletedChar)
        refreshSuggestionsAfterDeletion()
    }

    /** Records one plain single-character backspace into already-committed text (the final branch
     * of [onDeleteCharacter]). [UndoHistory.recordBackspace] merges consecutive ones into a single
     * step; every other action that mutates text or moves the cursor calls
     * [UndoHistory.breakCoalescing], so the merge only continues across an unbroken run of exactly
     * this kind of backspace. */
    private fun recordPlainCharDelete(char: Char) {
        undoHistory.recordBackspace(char)
        _uiState.update { it.copy(canUndo = undoHistory.canUndo, canRedo = undoHistory.canRedo) }
    }

    private fun onDeleteWord() {
        discardPendingLearn()
        forgetCorrectionMemory()
        undoHistory.breakCoalescing()
        words.clearBuffer()
        // Same selection-takes-priority rule as onDeleteCharacter() — swipe-left with everything
        // selected should clear the selection, not just delete one word next to the cursor.
        if (textEditor.hasSelection()) {
            val deleted = textEditor.selectedText()
            textEditor.deleteSelection()
            if (!deleted.isNullOrEmpty()) pushUndo(TextEdit(removed = deleted))
            refreshSuggestions()
            return
        }
        // A single trailing whitespace character (almost always a space — typed, tapped, or via
        // swipe-right) is its own swipe-left now, not bundled into the same swipe as the word
        // before it — real bug report: typing "hellow" then a space and swiping left deleted the
        // whole word *and* the space together in one gesture, with no way to just undo the space.
        // Matches how a punctuation/emoji run glued to the cursor is already its own swipe (see
        // wordBackwardDeletionParts's own doc) — whitespace is the same idea, just the opposite
        // direction (skipped *past* to find the word today; now consumed on its own first).
        // Multiple consecutive spaces are consumed one swipe at a time for the same reason.
        if (textEditor.textBeforeCursor(1).lastOrNull()?.isWhitespace() == true) {
            val deletedChar = textEditor.textBeforeCursor(1)
            textEditor.deleteCharacterBackward()
            pushUndo(TextEdit(removed = deletedChar))
            refreshSuggestionsAfterDeletion()
            return
        }
        // Read before deleting — deleteWordBackward() doesn't report what it removed, and undo
        // needs the *exact* text back (including whatever whitespace deleteWordBackward's own
        // scan consumes with it — a plain wordAtCursor().word would silently drop that on undo,
        // a real bug) to retype it precisely.
        val deletedText = textEditor.wordBackwardDeletionPreview()
        textEditor.deleteWordBackward()
        if (!deletedText.isNullOrEmpty()) pushUndo(TextEdit(removed = deletedText))
        refreshSuggestionsAfterDeletion()
    }

    /** Deleting a word/character can leave the cursor sitting right after a *previously*
     * committed word instead of at an empty/ordinary typing position — e.g. "okay i wont do "
     * with "do" deleted leaves "okay i wont " with nothing in the [WordTracker] buffer. Plain
     * [refreshSuggestions] only reacts to [WordTracker.lastCommitted] (typing-order bookkeeping that a
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
        if (words.isBufferNotEmpty) {
            refreshSuggestions()
            return
        }
        val wordBeforeCursor = textEditor.wordBeforeCursor()
        if (wordBeforeCursor == null) {
            refreshSuggestions()
            return
        }
        // Set directly (not via refreshSuggestions(), which would immediately overwrite it based
        // on words/words.lastCommitted — neither necessarily matches wordBeforeCursor
        // here, since this whole branch exists precisely for the case where a deletion left the
        // cursor sitting after a word neither of those is tracking).
        updateEmojiSuggestions(wordBeforeCursor.word)
        val alternatives = wordAlternatives(wordBeforeCursor.word)
        if (alternatives.isEmpty()) {
            refreshPlainPrediction()
            return
        }
        activeCorrection = ActiveCorrection(
            mode = CorrectionApplyMode.RETROACTIVE,
            originalWord = wordBeforeCursor.word,
            occupiedBefore = wordBeforeCursor.word.length,
            occupiedAfter = 0,
            separator = wordBeforeCursor.separator,
        )
        words.retargetLastCommitted(wordBeforeCursor.word)
        suggestionCycleIndex = -1
        _uiState.update { it.copy(suggestions = alternatives, firstSuggestionKind = SuggestionKind.CORRECTION) }
    }

    /**
     * Drops everything that describes a word in progress, for an edit that puts text on screen
     * which the typing-order bookkeeping cannot account for — a paste, an extension insertion, an
     * undo. None of it describes the new text, and leaving any of it set makes the next correction
     * act on a word that is no longer where it thinks.
     *
     * Note this deliberately does *not* clear the committed-word history: the text before the
     * insertion point is unchanged, so it is still valid context for prediction. That is the
     * difference between this and [WordTracker.clear], which a new field warrants and this does
     * not.
     */
    private fun resetTypingState() {
        words.clearBuffer()
        activeCorrection = null
        forgetCorrectionMemory()
    }

    /** Forgets that an autocorrect just happened, and that one was just reverted — so the next
     * backspace can't undo a swap that is no longer the most recent thing to have occurred, and
     * the next word boundary can't re-apply a correction the user already rejected. Cycling stops
     * too, since the candidate list it indexes into is about to be replaced. */
    private fun forgetCorrectionMemory() {
        lastAutocorrect = null
        revertedWord = null
        suggestionCycleIndex = -1
    }

    private fun pushUndo(event: TextEdit) {
        undoHistory.record(event)
        // Every caller except recordPlainCharDelete's own path wants coalescing off; UndoHistory
        // handles that itself, so there is nothing to repeat at each call site.
        _uiState.update { it.copy(canUndo = undoHistory.canUndo, canRedo = undoHistory.canRedo) }
    }

    /** Reverses the most recent tracked text edit — see [TextEdit]'s doc for exactly what's
     * covered. Puts back the *exact* recorded text (which already includes the real
     * separator/whitespace involved), so undo/redo round-trip losslessly instead of the old
     * scheme's hardcoded-space assumption. */
    fun undo() {
        val event = undoHistory.undo() ?: return
        textEditor.replaceBackward(event.inserted.length, event.removed)
        afterUndoOrRedo()
    }

    fun redo() {
        val event = undoHistory.redo() ?: return
        textEditor.replaceBackward(event.removed.length, event.inserted)
        afterUndoOrRedo()
    }

    /** Undo and redo land the cursor somewhere the typing-order bookkeeping can no longer describe,
     * so all of it is dropped and suggestions are re-derived from the live text instead. */
    private fun afterUndoOrRedo() {
        discardPendingLearn()
        _uiState.update { it.copy(canUndo = undoHistory.canUndo, canRedo = undoHistory.canRedo) }
        resetTypingState()
        undoHistory.breakCoalescing()
        refreshSuggestionsAfterDeletion()
    }

    /**
     * Ends the word in progress, and lets the personal model learn from it.
     *
     * Implicit learning was previously removed outright, because it used to mark every finished
     * word "known" — including uncaught typos, which then became **immune to correction forever**
     * (confirmed on a device). It is safe again only because [PersonalLanguageModel] separates the
     * two consequences that were conflated: a word picked up here influences *ranking*
     * immediately but earns *correction immunity* only after several separate uses, while an
     * explicit swipe-up save still earns it at once. A typo is a slip, and slips don't reliably
     * repeat.
     *
     * Skipped entirely while incognito (a password field, or the user's own toggle), and skipped
     * for a word autocorrect has just rewritten — [lastAutocorrect] means what is on screen is the
     * engine's guess, not a word the user chose, and learning from it would let the engine
     * reinforce its own corrections.
     *
     * The learn is **deferred**, not performed here — see [pendingLearn].
     */
    private fun flushWordBuffer(separator: String = "") {
        suggestionCycleIndex = -1
        val committed = words.commitBufferedWord() ?: return
        val word = committed.word
        pushUndo(TextEdit(inserted = word + separator))
        // Whatever was staged at the previous word boundary has now survived an entire further
        // word without being deleted or corrected, so it counts as deliberate.
        commitPendingLearn()
        pendingLearn = if (incognitoPreferences.shouldLearn() && lastAutocorrect == null &&
            word.all { it.isLetter() }
        ) {
            // The word before this one, read before the commit shifted it — see
            // [WordTracker.commitWord] for why that comes back from the call rather than being
            // read off a field afterwards.
            PendingLearn(word = word, previousWord = committed.previousWord)
        } else {
            null
        }
    }

    /**
     * A word that has been committed to the text but not yet offered to the personal model.
     *
     * Learning used to happen the instant a word boundary was crossed, which meant the keyboard
     * recorded words the user visibly rejected a moment later. Type "shoukd", press space, notice
     * it, backspace and retype: "shoukd" had already been learned on the space, and nothing
     * afterwards took it back. Same for fixing it by tapping a suggestion. It never earned
     * correction immunity — [PersonalLanguageModel.IMPLICIT_TRUST_THRESHOLD] takes three uses for
     * that, and that part was working — but it did pick up a ranking boost and it did show up in
     * Settings' "Learned words" list, which is what makes it look like the keyboard is memorising
     * typos. It is.
     *
     * So a word now has to *survive* to be learned. It is staged here at its own word boundary and
     * only handed to the model at the **next** one, by which point the user has typed a whole
     * further word without going back to fix it. Anything that suggests the word was not what they
     * meant — a backspace, a word delete, accepting a suggestion for it, an undo, leaving the
     * field — discards the staged word instead ([discardPendingLearn]).
     *
     * Deliberately conservative: a backspace anywhere discards the staged word, even one nowhere
     * near it. Failing to learn costs nothing (the word is learned the next time it is typed
     * cleanly) while learning a rejected word is the bug being fixed, so the asymmetry is the right
     * way round.
     *
     * This is the same shape as AOSP's `UserHistoryDictionary`, which is permissive about *adding*
     * (`FREQUENCY_FOR_TYPED = 2`) but makes entries prove themselves before they stick, via a
     * forgetting curve and a validity flag that decide whether a given entry is even persisted.
     * Neither Gboard nor iOS publishes its thresholds, so there is no number to copy from them —
     * what is copyable is the principle, which all of them share: one keystroke is not evidence.
     */
    private data class PendingLearn(val word: String, val previousWord: String?)

    private var pendingLearn: PendingLearn? = null

    private fun commitPendingLearn() {
        val pending = pendingLearn ?: return
        pendingLearn = null
        // Re-checked rather than trusted from staging time: the user may have switched the keyboard
        // into incognito in between, and a word typed before that flag went up is still a word they
        // don't want recorded.
        if (!incognitoPreferences.shouldLearn()) return
        scope.launch { predictionEngine.recordAcceptedWord(pending.word, pending.previousWord) }
    }

    /** Drops the staged word — the user did something that suggests it wasn't what they meant. See
     * [pendingLearn]. */
    private fun discardPendingLearn() {
        pendingLearn = null
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
        // Closes quick access on the way in: both occupy the key-grid slot, so leaving the flag set
        // would mean the panel reappears the moment the extension closes.
        _uiState.update { it.copy(activeExtensionId = id, quickAccessOpen = false) }
    }

    /** Cheap, synchronous static-table lookup (see [WordEmojiSuggestions]) — unlike word
     * suggestions/predictions, never worth a background [refreshJob] of its own. */
    private fun updateEmojiSuggestions(word: String?) {
        // Toned for display as well as insertion, so a chip shows what tapping it produces.
        // onEmojiSuggestionAccepted re-applies the tone to whatever it is handed, which is a no-op
        // for an already-toned chip (apply strips before re-adding) — so the two can't disagree.
        val tone = emojiSkinTone()
        val emoji = word?.let(WordEmojiSuggestions::suggest).orEmpty().map(tone::apply)
        _uiState.update { it.copy(emojiSuggestions = emoji) }
    }

    /** Tapping an emoji-suggestion chip (see [KeyboardUiState.emojiSuggestions]) just inserts it
     * next to whatever's already there — entirely independent of [activeCorrection]/word-cycling
     * state, since the emoji isn't replacing or completing the word, only riding along with it. */
    fun onEmojiSuggestionAccepted(rawEmoji: String) {
        val emoji = emojiSkinTone().apply(rawEmoji)
        textEditor.insertText(emoji)
        // One tap, one undo step — and an emoji is often a surrogate pair, so "just backspace it"
        // is not reliably one keypress either.
        pushUndo(TextEdit(inserted = emoji))
        _uiState.update { it.copy(emojiSuggestions = emptyList()) }
    }

    private fun refreshSuggestions(checkContextualCorrection: Boolean = false) {
        // Cancels any in-flight query from the previous keystroke first — without this, a slow
        // query for an earlier (now-stale) prefix can resolve after a faster later one and
        // overwrite the suggestion strip with outdated results.
        refreshJob?.cancel()
        val prefix = words.bufferedWord
        updateEmojiSuggestions(prefix.ifEmpty { words.lastCommitted.takeIf { checkContextualCorrection } })

        if (prefix.isNotEmpty()) {
            // Set synchronously — it is only bookkeeping, and cycling/revert need it available
            // before the expensive candidate scan finishes on another thread.
            activeCorrection = ActiveCorrection(
                mode = CorrectionApplyMode.LIVE_BUFFER,
                originalWord = prefix,
                occupiedBefore = prefix.length,
                occupiedAfter = 0,
                separator = "",
            )
            refreshJob = scope.launch {
                show(
                    suggestionComposer.forWordInProgress(
                        prefix = prefix,
                        previousWord = words.lastCommitted,
                        beforePreviousWord = words.previousToLastCommitted,
                    ),
                )
            }
            return
        }

        val target = words.lastCommittedCased.takeIf { checkContextualCorrection }
        if (target != null) {
            refreshJob = scope.launch {
                val result = suggestionComposer.forFinishedWord(target, words.previousToLastCommitted)
                if (result.isEmpty) {
                    refreshPlainPrediction()
                    return@launch
                }
                activeCorrection = ActiveCorrection(
                    mode = CorrectionApplyMode.RETROACTIVE,
                    originalWord = target,
                    occupiedBefore = target.length,
                    occupiedAfter = 0,
                    separator = words.boundarySeparator,
                )
                show(result)
            }
            return
        }

        refreshPlainPrediction()
    }

    /** Nothing to correct or vary — plain next-word prediction if enabled, and no active
     * correction, so accepting one of these types a fresh word rather than replacing anything. */
    private fun refreshPlainPrediction() {
        activeCorrection = null
        if (!predictionPreferences.settings.value.nextWordPredictionEnabled) {
            show(SuggestionComposer.Suggestions.None)
            return
        }
        refreshJob = scope.launch {
            show(suggestionComposer.nextWord(words.lastCommitted, words.previousToLastCommitted))
        }
    }

    /** [SuggestionComposer.alternatives] for a word the cursor happens to be sitting on, rather
     * than one being typed — used by the two cursor-driven paths ([onCursorMoved],
     * [refreshSuggestionsAfterDeletion]), which know the word but have no in-progress buffer. */
    private fun wordAlternatives(word: String): List<String> =
        suggestionComposer.alternatives(word, correctionContext())

    /**
     * Left context for correction scoring: the two words before whatever is being corrected.
     *
     * `AutocorrectIndex` ranks candidates by `-channelCost + λ·logP(candidate | context)`, so
     * supplying this is what lets "thus" lose to "this" when the preceding words actually favour
     * it. This replaced a separate `reorderByContext` pass that re-sorted the finished candidate
     * list by bigram count after the fact — which could only ever reorder what frequency had
     * already selected, and could not help a context-appropriate word that never made the list.
     */
    private fun correctionContext(): AutocorrectIndex.Context =
        autocorrectIndex.contextOf(words.lastCommitted, words.previousToLastCommitted)

    /** The single place suggestions reach the UI, so the strip and [SuggestionKind] can never
     * disagree about whether the first entry replaces a word or appends one. */
    private fun show(result: SuggestionComposer.Suggestions) {
        _uiState.update {
            it.copy(
                suggestions = result.words,
                firstSuggestionKind = if (result.fromCorrection) SuggestionKind.CORRECTION else SuggestionKind.PLAIN,
            )
        }
    }

    private companion object {
        const val PREFERRED_EXTENSION_ID = "builtin.emoji"
        const val SUGGESTION_LIMIT = 6
        // Matches the ~500ms window most mainstream keyboards use for double-tap-space-for-period.
        const val DOUBLE_TAP_SPACE_WINDOW_MS = 500L
        val SYMBOLS_LAYOUT_IDS = setOf(Layouts.Symbols1.id, Layouts.Symbols2.id)
    }
}
