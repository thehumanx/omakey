package dev.omakey.app.keyboard

import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.input.TextEditor
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.PredictionEngine
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

    fun resetForNewField() {
        _uiState.update {
            it.copy(
                layout = Layouts.QwertyEnUS,
                shiftOn = false,
                suggestions = emptyList(),
                activeExtensionId = null,
                topStripTab = TopStripTab.SUGGESTIONS,
            )
        }
        lastCommittedWord = null
        currentWordBuffer.clear()
        suggestionCycleIndex = -1
        lastAutocorrect = null
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
            SpecialKeyCode.SYMBOLS -> switchLayout(Layouts.Symbols1)
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
        repeat(currentWordBuffer.length) { textEditor.deleteCharacterBackward() }
        currentWordBuffer.clear()
        currentWordBuffer.append(word)
        word.forEach { textEditor.commitCharacter(it) }
        suggestionCycleIndex = index
    }

    private fun saveCurrentWordToDictionary() {
        val word = currentWordBuffer.toString()
        if (word.isBlank()) return
        autocorrectIndex.learn(word)
        scope.launch { predictionEngine.saveWord(word) }
    }

    /** Replaces whatever partial word is currently buffered/committed with the accepted suggestion. */
    fun onSuggestionAccepted(word: String) {
        lastAutocorrect = null
        val previous = lastCommittedWord
        repeat(currentWordBuffer.length) { textEditor.deleteCharacterBackward() }
        currentWordBuffer.clear()
        word.forEach { textEditor.commitCharacter(it) }
        textEditor.commitCharacter(' ')
        lastCommittedWord = word.lowercase()
        autocorrectIndex.learn(word)
        scope.launch { predictionEngine.recordAcceptedWord(word, previous) }
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
        } else {
            flushWordBuffer()
        }
        refreshSuggestions()
        if (_uiState.value.shiftOn) {
            _uiState.update { it.copy(shiftOn = false) } // one-shot shift, matches typical mobile keyboard behavior
        }
    }

    private fun onSpace() {
        maybeAutocorrectBufferedWord()
        flushWordBuffer()
        textEditor.insertSpace()
        refreshSuggestions()
    }

    private fun onEnter() {
        maybeAutocorrectBufferedWord()
        flushWordBuffer()
        textEditor.insertNewline()
    }

    /** Checked before every word-boundary commit (space/punctuation/enter). Replaces the
     * just-typed word in place if [AutocorrectIndex] is confident it's a typo of a much more
     * common word, preserving the original capitalization pattern. No-ops (and clears any stale
     * undo record) otherwise. */
    private fun maybeAutocorrectBufferedWord() {
        lastAutocorrect = null
        val typed = currentWordBuffer.toString()
        if (typed.isEmpty()) return
        val correctedLower = autocorrectIndex.correct(typed) ?: return
        val corrected = matchCase(typed, correctedLower)
        if (corrected == typed) return
        repeat(typed.length) { textEditor.deleteCharacterBackward() }
        corrected.forEach { textEditor.commitCharacter(it) }
        currentWordBuffer.clear()
        currentWordBuffer.append(corrected)
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
            repeat(record.corrected.length + 1) { textEditor.deleteCharacterBackward() }
            record.original.forEach { textEditor.commitCharacter(it) }
            currentWordBuffer.clear()
            currentWordBuffer.append(record.original)
            refreshSuggestions()
            return
        }
        lastAutocorrect = null
        suggestionCycleIndex = -1
        if (currentWordBuffer.isNotEmpty()) currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
        textEditor.deleteCharacterBackward()
        refreshSuggestions()
    }

    private fun onDeleteWord() {
        lastAutocorrect = null
        suggestionCycleIndex = -1
        currentWordBuffer.clear()
        textEditor.deleteWordBackward()
        refreshSuggestions()
    }

    private fun flushWordBuffer() {
        suggestionCycleIndex = -1
        val word = currentWordBuffer.toString()
        if (word.isNotEmpty()) {
            val previous = lastCommittedWord
            lastCommittedWord = word.lowercase()
            currentWordBuffer.clear()
            autocorrectIndex.learn(word)
            scope.launch { predictionEngine.recordAcceptedWord(word, previous) }
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

    private companion object {
        const val PREFERRED_EXTENSION_ID = "builtin.emoji"
    }

    private fun refreshSuggestions() {
        // Cancels any in-flight query from the previous keystroke first — without this, a slow
        // query for an earlier (now-stale) prefix can resolve after a faster later one and
        // overwrite the suggestion strip with outdated results.
        refreshJob?.cancel()
        val prefix = currentWordBuffer.toString()
        refreshJob = scope.launch {
            val suggestions = predictionEngine.suggestNext(lastCommittedWord, prefix, limit = 3)
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }
}
