package dev.omakey.app.keyboard

import android.view.inputmethod.EditorInfo
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.input.TextEditor
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.Calculator
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
     * letter" that clears itself after a single character (see [commitTypedChar]). */
    val capsLockOn: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val theme: OmakeyTheme = Presets.Dark,
    /** Mirrors [ThemeRepository.useSystemAccent] — kept alongside [theme] rather than inside it
     * since it's an orthogonal flag (see `resolveEffectiveTheme`, which is what actually applies
     * it), not a property of the theme data itself. */
    val useSystemAccent: Boolean = false,
    val activeExtensionId: String? = null,
    val layoutSettings: LayoutSettings = LayoutSettings(),
    val fontId: String = FontChoices.SYSTEM_DEFAULT,
    val gestureSettings: GestureSettings = GestureSettings(),
    val topStripTab: TopStripTab = TopStripTab.SUGGESTIONS,
    val firstSuggestionKind: SuggestionKind = SuggestionKind.PLAIN,
    /** Resolved from the focused field's [EditorInfo.imeOptions] each time a new field is
     * focused — drives both the Enter key's label (e.g. "Go", "Send") and what it actually does
     * on tap. [EditorInfo.IME_ACTION_NONE] (the default) means "just insert a newline." */
    val enterAction: Int = EditorInfo.IME_ACTION_NONE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** A short-lived confirmation ("hello learnt"/"hello unlearnt") shown in place of the
     * suggestion strip for ~0.5s — see [KeyboardViewModel.showBanner]. */
    val bannerMessage: String? = null,
)

/**
 * Owns the current typing session's state: active layout, shift state, suggestions, and which
 * extension panel (if any) is open. Routes all committed text through TextEditor and all
 * gesture-derived actions through the same handler as tap-derived ones, so gestures are strictly
 * additive to (not a separate path from) the tap-based key actions from M1.
 */
class KeyboardViewModel(
    private val textEditor: TextEditor,
    private val predictionEngine: PredictionEngine,
    private val autocorrectIndex: AutocorrectIndex,
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
    private val _uiState = MutableStateFlow(
        KeyboardUiState(
            theme = themeRepository.currentTheme.value,
            useSystemAccent = themeRepository.useSystemAccent.value,
            layoutSettings = layoutPreferences.settings.value,
            fontId = fontPreferences.fontId.value,
            gestureSettings = gesturePreferences.settings.value,
            topStripTab = topStripTabPreferences.tab.value,
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

    /** -1 = not currently cycling (buffer holds what was actually typed, or nothing's been
     * cycled yet); >=0 = index into the frozen suggestions snapshot currently applied, via swipe
     * up/down. */
    private var suggestionCycleIndex = -1
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

    /** Word-level undo/redo (Tools tab "Undo"/"Redo") — deliberately scoped to the two clearest,
     * most common actions: a word finishing (space/punctuation/Enter) and a word being deleted
     * (swipe-left). Autocorrect/suggestion corrections already have their own dedicated, more
     * precise revert gestures (backspace-reverts-the-swap, swipe up/down cycling) — folding those
     * into this same generic stack would fight with that existing, better-suited machinery rather
     * than complement it. Android's `InputConnection` has no standardized cross-app undo API
     * (`performContextMenuAction(android.R.id.undo)` isn't reliably implemented by host apps), so
     * this is necessarily Omakey's own app-level history, not a passthrough to the host app's. */
    private sealed class UndoEvent {
        data class Inserted(val word: String) : UndoEvent()
        data class Deleted(val word: String) : UndoEvent()
    }
    private val undoStack = ArrayDeque<UndoEvent>()
    private val redoStack = ArrayDeque<UndoEvent>()

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
        layoutPreferences.settings
            .onEach { settings -> _uiState.update { it.copy(layoutSettings = settings) } }
            .launchIn(scope)
        fontPreferences.fontId
            .onEach { id -> _uiState.update { it.copy(fontId = id) } }
            .launchIn(scope)
        gesturePreferences.settings
            .onEach { settings -> _uiState.update { it.copy(gestureSettings = settings) } }
            .launchIn(scope)
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
                layout = Layouts.QwertyEnUS,
                shiftOn = autocorrectPreferences.settings.value.autoCapitalizeEnabled && textEditor.textBeforeCursor(1).isEmpty(),
                capsLockOn = false,
                suggestions = emptyList(),
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
            SpecialKeyCode.LETTERS -> switchLayout(Layouts.QwertyEnUS)
            SpecialKeyCode.EXTENSIONS -> toggleExtensionPanel()
            else -> onCharacter(code)
        }
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

    /** Cycles right through the frozen suggestions snapshot; clamps at the last candidate. */
    fun onSwipeDown() {
        val suggestions = _uiState.value.suggestions
        if (suggestions.isEmpty()) return
        applySuggestion((suggestionCycleIndex + 1).coerceIn(0, suggestions.size - 1))
    }

    private fun applySuggestion(index: Int) {
        val word = _uiState.value.suggestions.getOrNull(index) ?: return
        lastAutocorrect = null
        revertedWord = null
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
        val original = active?.originalWord ?: currentWordBuffer.toString()
        if (original.isBlank()) return
        if (active != null) applyActiveCorrection(original)
        suggestionCycleIndex = -1
        when {
            autocorrectIndex.isUserAdded(original) -> {
                autocorrectIndex.unlearn(original)
                scope.launch { predictionEngine.deleteWord(original) }
                showBanner("$original unlearnt")
            }
            !autocorrectIndex.isKnown(original) -> {
                autocorrectIndex.learn(original)
                scope.launch { predictionEngine.saveWord(original) }
                showBanner("$original learnt")
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
        var char = rawChar
        if (_uiState.value.shiftOn) char = char.uppercaseChar()
        // Punctuation typed directly after a word (no space) is a word boundary too — correct
        // before committing the punctuation itself, so it lands after the fixed word.
        if (!char.isLetter()) {
            maybeAutocorrectBufferedWord()
        }
        textEditor.commitCharacter(char)
        if (char.isLetter()) {
            currentWordBuffer.append(char)
            refreshSuggestions()
        } else {
            flushWordBuffer()
            lastWordBoundarySeparator = char.toString()
            refreshSuggestions(checkContextualCorrection = true)
            if (char == '=') maybeShowCalculatorResult()
        }
        if (_uiState.value.shiftOn && !_uiState.value.capsLockOn) {
            _uiState.update { it.copy(shiftOn = false) } // one-shot shift, matches typical mobile keyboard behavior
        }
    }

    /** Inline calculator — typing "12+7=" offers "19" in the suggestion strip (never auto-
     * applied, same convention as every other correction). Called right after '=' itself has
     * already been committed as ordinary punctuation (see [commitTypedChar]), so [textBeforeCursor]
     * already includes it. Scoped to plain `+ - * /` per [Calculator]'s own doc — deliberately not
     * reusing [currentWordBuffer] (letters-only, never sees digits/operators in the first place),
     * a separate read of the actual committed text instead. */
    private fun maybeShowCalculatorResult() {
        val textBefore = textEditor.textBeforeCursor(64)
        if (textBefore.isEmpty() || textBefore.last() != '=') return
        val beforeEquals = textBefore.dropLast(1)
        val exprStart = beforeEquals.indexOfLast { it !in "0123456789+-*/. " }
        val expression = beforeEquals.substring(exprStart + 1)
        val result = Calculator.evaluate(expression) ?: return
        val formatted = Calculator.formatResult(result)
        activeCorrection = ActiveCorrection(
            mode = CorrectionApplyMode.RETROACTIVE,
            originalWord = expression + "=",
            occupiedBefore = expression.length + 1,
            occupiedAfter = 0,
            separator = "",
        )
        suggestionCycleIndex = -1
        _uiState.update { it.copy(suggestions = listOf(formatted), firstSuggestionKind = SuggestionKind.CORRECTION) }
    }

    private fun onSpace() {
        maybeAutocorrectBufferedWord()
        flushWordBuffer()
        textEditor.insertSpace()
        lastWordBoundarySeparator = " "
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
        flushWordBuffer()
        val action = _uiState.value.enterAction
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
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
            currentWordBuffer.clear()
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
        if (currentWordBuffer.isNotEmpty()) currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
        textEditor.deleteCharacterBackward()
        refreshSuggestions()
    }

    private fun onDeleteWord() {
        lastAutocorrect = null
        revertedWord = null
        suggestionCycleIndex = -1
        currentWordBuffer.clear()
        // Same selection-takes-priority rule as onDeleteCharacter() — swipe-left with everything
        // selected should clear the selection, not just delete one word next to the cursor.
        if (textEditor.hasSelection()) {
            textEditor.deleteSelection()
            refreshSuggestions()
            return
        }
        // Read before deleting — deleteWordBackward() doesn't report what it removed, and undo
        // needs the exact (case-preserved) text back to retype it.
        val deletedWord = textEditor.wordAtCursor()?.word
        textEditor.deleteWordBackward()
        if (!deletedWord.isNullOrBlank()) pushUndo(UndoEvent.Deleted(deletedWord))
        refreshSuggestions()
    }

    private fun pushUndo(event: UndoEvent) {
        undoStack.addLast(event)
        if (undoStack.size > UNDO_STACK_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        _uiState.update { it.copy(canUndo = true, canRedo = false) }
    }

    /** Reverses the most recent word commit or word deletion — see [UndoEvent]'s doc for exactly
     * what's covered. Deliberately mirrors the same primitives already used elsewhere
     * (`deleteWordBackward`/`commitCharacter`) rather than inventing a new text-editing path. */
    fun undo() {
        val event = undoStack.removeLastOrNull() ?: return
        when (event) {
            is UndoEvent.Inserted -> textEditor.deleteWordBackward()
            is UndoEvent.Deleted -> {
                event.word.forEach { textEditor.commitCharacter(it) }
                textEditor.commitCharacter(' ')
            }
        }
        redoStack.addLast(event)
        if (redoStack.size > UNDO_STACK_LIMIT) redoStack.removeFirst()
        currentWordBuffer.clear()
        suggestionCycleIndex = -1
        activeCorrection = null
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = true) }
        refreshSuggestions()
    }

    fun redo() {
        val event = redoStack.removeLastOrNull() ?: return
        when (event) {
            is UndoEvent.Inserted -> {
                event.word.forEach { textEditor.commitCharacter(it) }
                textEditor.commitCharacter(' ')
            }
            is UndoEvent.Deleted -> textEditor.deleteWordBackward()
        }
        undoStack.addLast(event)
        currentWordBuffer.clear()
        suggestionCycleIndex = -1
        activeCorrection = null
        _uiState.update { it.copy(canUndo = true, canRedo = redoStack.isNotEmpty()) }
        refreshSuggestions()
    }

    // Deliberately does NOT call autocorrectIndex.learn()/predictionEngine.recordAcceptedWord()
    // for ordinary typing — only the explicit swipe-up "save word" gesture
    // (revertAndMaybeSave) teaches the dictionary a new word. Every word boundary used to
    // silently learn whatever was just typed, including typos that weren't caught (e.g. because
    // autocorrect was off, or the typo wasn't a recognized one-edit neighbor of anything) — those
    // got permanently marked "known" the moment they were finished, immune to correction forever
    // after. Bigram/frequency data from the seeded corpus still drives prediction and completion;
    // it just no longer grows from casual typing.
    private fun flushWordBuffer() {
        suggestionCycleIndex = -1
        val word = currentWordBuffer.toString()
        if (word.isNotEmpty()) {
            previousToLastCommittedWord = lastCommittedWord
            lastCommittedWord = word.lowercase()
            currentWordBuffer.clear()
            pushUndo(UndoEvent.Inserted(word))
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

    /** [checkContextualCorrection] is true right after any word-boundary commit that leaves a
     * definite, known separator behind the finished word — space, punctuation, or Enter inserting
     * a literal newline (see [onSpace]/[commitTypedChar]/[onEnter], and [lastWordBoundarySeparator]
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

        if (prefix.isNotEmpty()) {
            val alternatives = wordAlternatives(prefix)
            activeCorrection = ActiveCorrection(
                mode = CorrectionApplyMode.LIVE_BUFFER,
                originalWord = prefix,
                occupiedBefore = prefix.length,
                occupiedAfter = 0,
                separator = "",
            )
            refreshJob = scope.launch {
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
            val rawAlternatives = wordAlternatives(target)
            if (rawAlternatives.isNotEmpty()) {
                activeCorrection = ActiveCorrection(
                    mode = CorrectionApplyMode.RETROACTIVE,
                    originalWord = target,
                    occupiedBefore = target.length,
                    occupiedAfter = 0,
                    separator = lastWordBoundarySeparator,
                )
                val context = previousToLastCommittedWord
                refreshJob = scope.launch {
                    val ranked = reorderByContext(context, rawAlternatives)
                    _uiState.update { it.copy(suggestions = ranked, firstSuggestionKind = SuggestionKind.CORRECTION) }
                }
                return
            }
        }

        // Nothing to correct/vary — plain next-word prediction if enabled, no active correction
        // (accepting one of these types a fresh word, doesn't replace anything).
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
    }
}
