package dev.omakey.app.keyboard

import dev.omakey.core.input.TextEditor
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.predict.PredictionEngine
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class KeyboardUiState(
    val layout: KeyboardLayout = Layouts.QwertyEnUS,
    val shiftOn: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val theme: OmakeyTheme = Presets.Dark,
    val activeExtensionId: String? = null,
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
    val extensionRegistry: ExtensionRegistry,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(KeyboardUiState())
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    private var lastCommittedWord: String? = null
    private var currentWordBuffer = StringBuilder()

    val extensionHost = object : ExtensionHost {
        override fun insertText(text: String) {
            text.forEach { textEditor.commitCharacter(it) }
        }
        override fun close() {
            _uiState.update { it.copy(activeExtensionId = null) }
        }
    }

    fun resetForNewField() {
        _uiState.update { it.copy(layout = Layouts.QwertyEnUS, shiftOn = false, suggestions = emptyList(), activeExtensionId = null) }
        lastCommittedWord = null
        currentWordBuffer.clear()
    }

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
    fun onSwipeUp() = toggleShift()
    fun onSwipeDown() {
        val onSymbols = _uiState.value.layout.id == Layouts.Symbols1.id
        switchLayout(if (onSymbols) Layouts.QwertyEnUS else Layouts.Symbols1)
    }

    /** Replaces whatever partial word is currently buffered/committed with the accepted suggestion. */
    fun onSuggestionAccepted(word: String) {
        val previous = lastCommittedWord
        repeat(currentWordBuffer.length) { textEditor.deleteCharacterBackward() }
        currentWordBuffer.clear()
        word.forEach { textEditor.commitCharacter(it) }
        textEditor.commitCharacter(' ')
        lastCommittedWord = word.lowercase()
        scope.launch { predictionEngine.recordAcceptedWord(word, previous) }
        refreshSuggestions()
    }

    private fun onCharacter(code: Int) {
        var char = Character.toChars(code)[0]
        if (_uiState.value.shiftOn) char = char.uppercaseChar()
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
        flushWordBuffer()
        textEditor.insertSpace()
        refreshSuggestions()
    }

    private fun onEnter() {
        flushWordBuffer()
        textEditor.insertNewline()
    }

    private fun onDeleteCharacter() {
        if (currentWordBuffer.isNotEmpty()) currentWordBuffer.deleteCharAt(currentWordBuffer.length - 1)
        textEditor.deleteCharacterBackward()
        refreshSuggestions()
    }

    private fun onDeleteWord() {
        currentWordBuffer.clear()
        textEditor.deleteWordBackward()
        refreshSuggestions()
    }

    private fun flushWordBuffer() {
        val word = currentWordBuffer.toString()
        if (word.isNotEmpty()) {
            val previous = lastCommittedWord
            lastCommittedWord = word.lowercase()
            currentWordBuffer.clear()
            scope.launch { predictionEngine.recordAcceptedWord(word, previous) }
        }
    }

    private fun toggleShift() {
        _uiState.update { it.copy(shiftOn = !it.shiftOn) }
    }

    private fun switchLayout(layout: KeyboardLayout) {
        _uiState.update { it.copy(layout = layout) }
    }

    private fun toggleExtensionPanel() {
        val id = extensionRegistry.all().firstOrNull()?.id ?: return
        _uiState.update { it.copy(activeExtensionId = if (it.activeExtensionId == id) null else id) }
    }

    private fun refreshSuggestions() {
        val prefix = currentWordBuffer.toString()
        scope.launch {
            val suggestions = predictionEngine.suggestNext(lastCommittedWord, prefix, limit = 3)
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }
}
