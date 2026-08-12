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

/** What kind of thing suggestion slot 0 currently is, if anything special — drives both the
 * strip's quoted-vs-plain rendering and, more importantly, *how* accepting it is applied, since
 * each correction kind touches different text: [LIVE_CORRECTION] replaces the word still being
 * typed (see [KeyboardViewModel.commitCorrection]); [CONTEXTUAL_CORRECTION] retroactively replaces
 * the word *before* the one currently being typed (see
 * [KeyboardViewModel.applyContextualCorrection]); [CURSOR_CORRECTION] replaces whatever word the
 * cursor is currently sitting inside, independent of typing order entirely — e.g. the cursor
 * tapped into the middle of an already-committed word (see
 * [KeyboardViewModel.applyCursorCorrection]). Applying one via another's code path would edit the
 * wrong text. */
enum class SuggestionKind { PLAIN, LIVE_CORRECTION, CONTEXTUAL_CORRECTION, CURSOR_CORRECTION }

data class KeyboardUiState(
    val layout: KeyboardLayout = Layouts.QwertyEnUS,
    val shiftOn: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val theme: OmakeyTheme = Presets.Dark,
    val activeExtensionId: String? = null,
    val layoutSettings: LayoutSettings = LayoutSettings(),
    val fontId: String = FontChoices.SYSTEM_DEFAULT,
    val gestureSettings: GestureSettings = GestureSettings(),
    val topStripTab: TopStripTab = TopStripTab.SUGGESTIONS,
    /** What `suggestions[0]` is, if anything special — see [SuggestionKind]. Lets the strip render
     * it visually distinct (quoted), same convention Gboard/Fleksy use to signal "this is a fix,"
     * not just another word choice. */
    val firstSuggestionKind: SuggestionKind = SuggestionKind.PLAIN,
    /** Resolved from the focused field's [EditorInfo.imeOptions] each time a new field is
     * focused — drives both the Enter key's label (e.g. "Go", "Send") and what it actually does
     * on tap. [EditorInfo.IME_ACTION_NONE] (the default) means "just insert a newline." */
    val enterAction: Int = EditorInfo.IME_ACTION_NONE,
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
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(
        KeyboardUiState(
            theme = themeRepository.currentTheme.value,
            layoutSettings = layoutPreferences.settings.value,
            fontId = fontPreferences.fontId.value,
            gestureSettings = gesturePreferences.settings.value,
        ),
    )
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    private var lastCommittedWord: String? = null

    /** Whatever [lastCommittedWord] was immediately *before* the current [lastCommittedWord] —
     * i.e. the bigram context for the word that was just finished. Needed for the post-space
     * contextual "did you mean" check (see [refreshSuggestions]): once a word is committed,
     * [lastCommittedWord] itself becomes that word, so checking "is *this* committed word a good
     * fit for what came before it" needs the word before *that* one, captured before it's
     * overwritten. */
    private var previousToLastCommittedWord: String? = null

    /** The literal separator text that ended [lastCommittedWord] — a space, a newline (Enter
     * inserting one rather than firing an editor action), or a punctuation character. Needed by
     * [applyContextualCorrection] to know exactly how many characters sit between the target word
     * and the cursor, and what to retype after it; hardcoding a space there would silently corrupt
     * "word.<contextual fix>" into "word.<fix> " (extra space) or "word\n<fix>" into
     * "word\n<fix> " (newline replaced by a space). */
    private var lastWordBoundarySeparator: String = " "
    private var currentWordBuffer = StringBuilder()

    /** -1 = not currently cycling (buffer holds what was actually typed); >=0 = index into the
     * frozen suggestions snapshot currently applied to the buffer, via swipe up/down. */
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

    /** Set by [onCursorMoved] when the cursor is sitting inside a word (anywhere — not just at
     * the end of it) that has a live correction candidate, independent of [currentWordBuffer]'s
     * typing-order tracking entirely. Cleared once acted on or once the cursor moves somewhere
     * that no longer has one. */
    private var cursorCorrectionTarget: TextEditor.WordAtCursor? = null

    init {
        // Keeps an already-open keyboard in sync if the user changes theme/layout settings from
        // Settings while the IME view is alive (same process, different Activity).
        themeRepository.currentTheme
            .onEach { theme -> _uiState.update { it.copy(theme = theme) } }
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
                shiftOn = false,
                suggestions = emptyList(),
                firstSuggestionKind = SuggestionKind.PLAIN,
                activeExtensionId = null,
                topStripTab = TopStripTab.SUGGESTIONS,
                enterAction = enterAction,
            )
        }
        lastCommittedWord = null
        previousToLastCommittedWord = null
        currentWordBuffer.clear()
        suggestionCycleIndex = -1
        lastAutocorrect = null
        revertedWord = null
        cursorCorrectionTarget = null
    }

    fun selectTopStripTab(tab: TopStripTab) {
        _uiState.update { it.copy(topStripTab = tab) }
    }

    fun onSelectAll() = textEditor.selectAll()
    fun onCopy() = textEditor.copySelection()
    fun onCut() = textEditor.cutSelection()
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

    /** Cycles left through the frozen suggestions snapshot (does not re-query, so the candidate
     * set stays stable while cycling). At the leftmost candidate — or when there's nothing to
     * cycle at all — saves the current word to the local dictionary instead, since there's
     * nothing further "on the left" to move to. */
    fun onSwipeUp() {
        val suggestions = _uiState.value.suggestions
        when {
            suggestions.isEmpty() -> saveCurrentWordToDictionary()
            suggestionCycleIndex == -1 -> applySuggestion(0)
            suggestionCycleIndex == 0 -> saveCurrentWordToDictionary()
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
        if (index == 0 && _uiState.value.firstSuggestionKind == SuggestionKind.CURSOR_CORRECTION) {
            applyCursorCorrection(word)
            suggestionCycleIndex = -1
            return
        }
        if (index == 0 && _uiState.value.firstSuggestionKind == SuggestionKind.CONTEXTUAL_CORRECTION) {
            applyContextualCorrection(word)
            suggestionCycleIndex = -1
            return
        }
        val wasSplit = commitCorrection(word)
        if (wasSplit) {
            // The buffer now holds the split's second word (e.g. "is" after "thisbis" -> "this
            // is"), not the original text the frozen suggestion list was computed for — cycling
            // further via that stale list would apply completions for the wrong word. A fresh
            // query for the new buffer is the only thing that makes sense here.
            suggestionCycleIndex = -1
            refreshSuggestions()
        } else {
            suggestionCycleIndex = index
        }
    }

    private fun saveCurrentWordToDictionary() {
        val word = currentWordBuffer.toString()
        if (word.isBlank()) return
        autocorrectIndex.learn(word)
        scope.launch { predictionEngine.saveWord(word) }
    }

    /** Replaces whatever partial word is currently buffered/committed with the accepted
     * suggestion, then finishes it (adds a trailing space) — tapping means "I'm done with this
     * word," unlike swipe-accepting via [applySuggestion], which leaves the word open for further
     * editing. Does not learn/record anything (see [flushWordBuffer]'s doc) — the word came from
     * the suggestion strip, so it was already a known word or an already-vetted correction. */
    fun onSuggestionAccepted(word: String) {
        lastAutocorrect = null
        revertedWord = null
        // Slot 0's dedup guarantee (see refreshSuggestions) means a correction word never appears
        // anywhere else in the list, so an exact match against suggestions[0] here is enough to
        // know this tap is that suggestion, without needing the tap's index plumbed all the way
        // through from the UI layer.
        val isSlotZero = word == _uiState.value.suggestions.firstOrNull()
        if (isSlotZero && _uiState.value.firstSuggestionKind == SuggestionKind.CURSOR_CORRECTION) {
            applyCursorCorrection(word)
            return
        }
        if (isSlotZero && _uiState.value.firstSuggestionKind == SuggestionKind.CONTEXTUAL_CORRECTION) {
            applyContextualCorrection(word)
            return
        }
        commitCorrection(word)
        val finished = currentWordBuffer.toString()
        textEditor.commitCharacter(' ')
        currentWordBuffer.clear()
        // Read *after* commitCorrection — for a two-word split it already advanced
        // lastCommittedWord to the split's first word.
        previousToLastCommittedWord = lastCommittedWord
        lastCommittedWord = finished.lowercase()
        refreshSuggestions()
    }

    /** Deletes the currently-buffered raw text and commits [replacement] in its place, correctly
     * handling two possible shapes:
     * - A single word (the common case — an ordinary suggestion, next-word prediction, or
     *   single-word typo fix): committed and left as the new, still-editable buffer content.
     * - Two words separated by one space (a "missing space" split correction, e.g. "thisbis" ->
     *   "this is" — see [AutocorrectIndex.correct]): the embedded space is a real word boundary,
     *   so the first word is committed *and* immediately learned/flushed (bigram recorded against
     *   whatever word preceded it), while the second word becomes the new active buffer — exactly
     *   as if the user had typed it fresh after a real space press. Treating the whole "this is"
     *   string as one opaque buffer value instead would silently corrupt future learning: the next
     *   [flushWordBuffer] would persist `"this is"` as a single dictionary "word" containing a
     *   literal space.
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
        // No learn()/recordAcceptedWord() here either — see flushWordBuffer's doc. firstWord came
        // from AutocorrectIndex.correct()'s split logic, so it's already a known dictionary word;
        // nothing new to teach regardless.
        previousToLastCommittedWord = lastCommittedWord
        lastCommittedWord = firstWord.lowercase()
        secondWord.forEach { textEditor.commitCharacter(it) }
        currentWordBuffer.append(secondWord)
        return true
    }

    /** Applies a [SuggestionKind.CONTEXTUAL_CORRECTION] — retroactively swaps out
     * [lastCommittedWord] (already sitting in the text field, cursor now positioned just past
     * whatever separator ended it — see [lastWordBoundarySeparator]) for [replacement], rather
     * than editing [currentWordBuffer] like [commitCorrection] does. Only ever offered right
     * after a word-boundary commit (see [refreshSuggestions]), so the cursor is always exactly
     * [lastWordBoundarySeparator]'s length past the target word — safe to assume without
     * re-deriving it from the text field. */
    private fun applyContextualCorrection(replacement: String) {
        val target = lastCommittedWord ?: return
        val separator = lastWordBoundarySeparator
        repeat(target.length + separator.length) { textEditor.deleteCharacterBackward() }
        replacement.forEach { textEditor.commitCharacter(it) }
        separator.forEach { textEditor.commitCharacter(it) }
        // No learn()/recordAcceptedWord() — see flushWordBuffer's doc; replacement is already a
        // known dictionary word by construction (it came from AutocorrectIndex.correct() or
        // realWordNeighbors()), so there's nothing new to teach.
        lastCommittedWord = replacement.lowercase()
        refreshSuggestions()
    }

    /** Called whenever the cursor/selection changes for a reason outside the normal typing flow —
     * a tap elsewhere in the text, arrow-key navigation, autofill, etc (see
     * `OmakeyInputMethodService.onUpdateSelection`). Independent of [currentWordBuffer]'s typing-
     * order tracking entirely: derives "the word at the cursor" straight from the live text via
     * [TextEditor.wordAtCursor] and checks *that* word for a correction, which is the only way to
     * catch "cursor moved into the middle of an already-committed word" — nothing about normal
     * keystroke handling ever sees that case, since it isn't a keystroke at all. */
    fun onCursorMoved() {
        val wordAtCursor = textEditor.wordAtCursor()
        // The cursor sitting right at the end of a word that exactly matches what's actively
        // being typed is the ordinary, extremely common case (every keystroke moves the cursor)
        // — already handled by the normal typing pipeline, and re-checking it here on every
        // single character would be redundant work racing that pipeline's own suggestions update.
        val isOrdinaryTypingPosition = wordAtCursor != null &&
            wordAtCursor.charsAfterCursor == 0 &&
            wordAtCursor.word == currentWordBuffer.toString()
        if (wordAtCursor == null || isOrdinaryTypingPosition) {
            if (cursorCorrectionTarget != null) {
                cursorCorrectionTarget = null
                refreshSuggestions()
            }
            return
        }
        // Deliberately NOT gated on autocorrectEnabled — that toggle controls only the silent,
        // automatic correction in maybeAutocorrectBufferedWord(). Every suggestion-strip
        // correction (this one included) is manually swipe/tap-accepted, so it stays available
        // regardless of whether auto-apply is on.
        val correctedLower = autocorrectIndex.correct(wordAtCursor.word)
        if (correctedLower == null) {
            if (cursorCorrectionTarget != null) {
                cursorCorrectionTarget = null
                refreshSuggestions()
            }
            return
        }
        val corrected = matchCase(wordAtCursor.word, correctedLower)
        cursorCorrectionTarget = wordAtCursor
        _uiState.update {
            it.copy(
                suggestions = (listOf(corrected) + it.suggestions.filterNot { s -> s.equals(corrected, ignoreCase = true) })
                    .take(SUGGESTION_LIMIT),
                firstSuggestionKind = SuggestionKind.CURSOR_CORRECTION,
            )
        }
    }

    /** Applies a [SuggestionKind.CURSOR_CORRECTION] — replaces [cursorCorrectionTarget] (wherever
     * in the text it actually is, independent of [currentWordBuffer]) with [replacement] via
     * [TextEditor.replaceWordAtCursor], rather than assuming the word sits at the end of the
     * buffer or right before the cursor the way [commitCorrection]/[applyContextualCorrection] do. */
    private fun applyCursorCorrection(replacement: String) {
        val target = cursorCorrectionTarget ?: return
        textEditor.replaceWordAtCursor(target, replacement)
        // No learn() — see flushWordBuffer's doc; replacement is already a known word.
        cursorCorrectionTarget = null
        refreshSuggestions()
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
        }
        if (_uiState.value.shiftOn) {
            _uiState.update { it.copy(shiftOn = false) } // one-shot shift, matches typical mobile keyboard behavior
        }
    }

    private fun onSpace() {
        maybeAutocorrectBufferedWord()
        flushWordBuffer()
        textEditor.insertSpace()
        lastWordBoundarySeparator = " "
        refreshSuggestions(checkContextualCorrection = true)
    }

    private fun onEnter() {
        maybeAutocorrectBufferedWord()
        flushWordBuffer()
        val action = _uiState.value.enterAction
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            textEditor.insertNewline()
            lastWordBoundarySeparator = "\n"
            refreshSuggestions(checkContextualCorrection = true)
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
     * undo record) otherwise. */
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
        textEditor.deleteWordBackward()
        refreshSuggestions()
    }

    // Deliberately does NOT call autocorrectIndex.learn()/predictionEngine.recordAcceptedWord()
    // for ordinary typing — only the explicit swipe-up "save word" gesture
    // (saveCurrentWordToDictionary) teaches the dictionary a new word. Every word boundary used to
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
        }
    }

    private fun toggleShift() {
        _uiState.update { it.copy(shiftOn = !it.shiftOn) }
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
     * for why an editor action like "Go"/"Send" doesn't qualify). It additionally checks whether
     * the word *just* finished (not the one now being typed) has a
     * much better contextual fit than what was actually typed, catching "real-word errors" plain
     * frequency-based correction can't (see [SuggestionKind.CONTEXTUAL_CORRECTION]). */
    private fun refreshSuggestions(checkContextualCorrection: Boolean = false) {
        // Cancels any in-flight query from the previous keystroke first — without this, a slow
        // query for an earlier (now-stale) prefix can resolve after a faster later one and
        // overwrite the suggestion strip with outdated results.
        refreshJob?.cancel()
        val prefix = currentWordBuffer.toString()
        // Computed synchronously (pure in-memory map lookups, same cost class as the existing
        // word-boundary check in maybeAutocorrectBufferedWord) so it's available immediately,
        // not gated behind the async prediction query below. Surfacing it as suggestion slot 0
        // is what lets a typo like "corrcet" offer "correct" via swipe/tap *before* a space is
        // ever pressed — a prefix search alone can't find it, since "corrcet" isn't a textual
        // prefix of "correct".
        val correction = correctionCandidate(prefix)
        val contextualTarget = lastCommittedWord.takeIf { checkContextualCorrection && prefix.isEmpty() }
        val contextualContext = previousToLastCommittedWord
        // The toggle only ever suppresses *next-word* prediction (querying with an empty prefix
        // right after finishing a word) — live completion of the word currently being typed
        // (non-empty prefix, e.g. "comp" -> "company") is a different, always-on feature; both
        // happen to share the same suggestNext() query shape, but they're conceptually distinct
        // and the toggle's name/description are scoped to just the "what comes next" half.
        val nextWordPredictionEnabled = predictionPreferences.settings.value.nextWordPredictionEnabled
        val skipQuery = prefix.isEmpty() && !nextWordPredictionEnabled
        refreshJob = scope.launch {
            val predicted = if (skipQuery) emptyList() else predictionEngine.suggestNext(lastCommittedWord, prefix, limit = SUGGESTION_LIMIT)
            var suggestions = if (correction != null) {
                (listOf(correction) + predicted.filterNot { it.equals(correction, ignoreCase = true) }).take(SUGGESTION_LIMIT)
            } else {
                predicted
            }
            var kind = if (correction != null) SuggestionKind.LIVE_CORRECTION else SuggestionKind.PLAIN

            if (kind == SuggestionKind.PLAIN && contextualTarget != null) {
                // Try a plain typo fix on the just-committed word first — this is what covers a
                // word that *would* have been auto-corrected but wasn't, because autocorrect is
                // toggled off (maybeAutocorrectBufferedWord respects that toggle; this suggestion
                // does not, see correctionCandidate's doc). Only falls back to the bigram-based
                // real-word-error check when the word is already a valid dictionary entry on its
                // own (correct() refuses to touch those), which is the case correct() alone can't
                // resolve regardless of the toggle.
                val altWord = autocorrectIndex.correct(contextualTarget)?.let { matchCase(contextualTarget, it) }
                    ?: contextualContext?.let { contextualCorrection(it, contextualTarget) }
                if (altWord != null) {
                    suggestions = (listOf(altWord) + suggestions.filterNot { it.equals(altWord, ignoreCase = true) })
                        .take(SUGGESTION_LIMIT)
                    kind = SuggestionKind.CONTEXTUAL_CORRECTION
                }
            }

            _uiState.update { it.copy(suggestions = suggestions, firstSuggestionKind = kind) }
        }
    }

    /** Same typo-correction logic [maybeAutocorrectBufferedWord] applies automatically at a word
     * boundary, but offered live as a suggestion-strip candidate while the word is still being
     * typed — lets swipe/tap accept the fix immediately instead of waiting for space/punctuation
     * to trigger the automatic version. Respects the same "just rejected via backspace" one-shot
     * suppression via [revertedWord] so a manually reverted correction doesn't immediately
     * reappear as a suggestion either. Deliberately NOT gated on the autocorrect toggle — that
     * setting controls only the silent, automatic correction in [maybeAutocorrectBufferedWord];
     * a manually swipe/tap-accepted suggestion here stays available regardless. */
    private fun correctionCandidate(typed: String): String? {
        if (typed.isEmpty()) return null
        if (typed == revertedWord) return null
        val correctedLower = autocorrectIndex.correct(typed) ?: return null
        return matchCase(typed, correctedLower)
    }

    /** Checks whether [target] (a word just committed, and itself a valid dictionary entry — the
     * only reason it wasn't already caught by [maybeAutocorrectBufferedWord]) is a much weaker fit
     * for what precedes it ([context]) than one of its one-edit neighbors, e.g. "thus" where
     * "this" would be the overwhelmingly more likely continuation of the previous word. Never
     * auto-applies — only ever offered as a suggestion-strip candidate the user chooses to accept,
     * unlike ordinary typo correction — real-word-error detection is inherently lower-confidence
     * (it's a judgment call about which of two *valid* words was meant), so silently rewriting
     * text on this signal alone would be too easy to get wrong. */
    private suspend fun contextualCorrection(context: String, target: String): String? {
        val neighbors = autocorrectIndex.realWordNeighbors(target)
        if (neighbors.isEmpty()) return null
        val targetRank = predictionEngine.bigramRank(context, target)
        var best: String? = null
        var bestRank = 0
        for (neighbor in neighbors) {
            val rank = predictionEngine.bigramRank(context, neighbor)
            if (rank > bestRank) {
                bestRank = rank
                best = neighbor
            }
        }
        // Both an absolute floor (some real, non-trivial usage history for this pairing) and a
        // relative one (meaningfully stronger than what was actually typed, not just marginally
        // ahead) — guards against flagging ordinary, correctly-typed word choices as "corrections"
        // just because they happen to have *any* bigram data for a rarer neighbor.
        if (best == null || bestRank < CONTEXTUAL_CORRECTION_MIN_RANK) return null
        if (bestRank < targetRank * CONTEXTUAL_CORRECTION_MULTIPLIER) return null
        return matchCase(target, best)
    }

    private companion object {
        const val PREFERRED_EXTENSION_ID = "builtin.emoji"
        const val SUGGESTION_LIMIT = 6
        const val CONTEXTUAL_CORRECTION_MIN_RANK = 1000
        const val CONTEXTUAL_CORRECTION_MULTIPLIER = 3
    }
}
