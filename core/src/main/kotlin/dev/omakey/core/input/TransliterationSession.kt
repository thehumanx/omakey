package dev.omakey.core.input


/**
 * One word being typed in Latin letters and shown in another script (AGENTS.md §66 Phase 9).
 *
 * Holds the Latin typed so far, the candidates for it, and which is selected; keeps the selected
 * one on screen as composing text, so the field shows नमस्ते while "namaste" is being typed and
 * nothing is committed until the word ends.
 *
 * The suggestion strip is [strip]: the candidates, then the Latin exactly as typed — mixing English
 * into Nepali is ordinary, and picking the Latin keeps it.
 *
 * Candidates are passed in on each call rather than computed here, so this stays free of the model
 * and testable against a fake input connection; the view model supplies them with context.
 */
class TransliterationSession(private val editor: TextEditor) {

    private val latin = StringBuilder()
    private var candidates: List<String> = emptyList()

    /** Index into [strip] of what is on screen. */
    var selected: Int = 0
        private set

    val typed: String get() = latin.toString()

    val isComposing: Boolean get() = latin.isNotEmpty()

    /** What the strip offers: candidates in the typing's case ("Privet" → "Привет"; a no-op for a
     * caseless script), then the Latin as typed if it isn't one of them. */
    val strip: List<String>
        get() = if (latin.isEmpty()) emptyList() else (candidates.map(::inTypedCase) + typed).distinct()

    /** Appends [letters] and recomputes candidates with [candidatesFor]. */
    fun type(letters: String, candidatesFor: (String) -> List<String>) {
        latin.append(letters)
        refresh(candidatesFor)
    }

    /** Removes the last Latin letter. False when nothing was being composed, so the caller can do
     * an ordinary backspace instead. */
    fun backspace(candidatesFor: (String) -> List<String>): Boolean {
        if (latin.isEmpty()) return false
        latin.deleteCharAt(latin.length - 1)
        if (latin.isEmpty()) {
            editor.cancelComposing()
            candidates = emptyList()
            selected = 0
        } else {
            refresh(candidatesFor)
        }
        return true
    }

    /** Shows strip entry [index] instead — swiping through candidates. */
    fun select(index: Int) {
        val options = strip
        if (options.isEmpty()) return
        selected = index.mod(options.size)
        editor.setComposing(options[selected])
    }

    /** Commits the selected entry followed by [separator] and ends the word; returns what was
     * committed (without the separator), or null if nothing was being composed. */
    fun commit(separator: String = ""): String? {
        val options = strip
        if (options.isEmpty()) return null
        return commit(options[selected.coerceIn(options.indices)], separator)
    }

    /** Commits [word] (a strip entry the user tapped) followed by [separator]. */
    fun commit(word: String, separator: String): String {
        editor.commitComposing(word + separator)
        clear()
        return word
    }

    /** The cursor moved elsewhere mid-word: leave what's shown as it is and stop composing. */
    fun abandon() {
        if (latin.isEmpty()) return
        editor.finishComposing()
        clear()
    }

    /** Forgets the word without touching the field — a new field, where it is already gone. */
    fun clear() {
        latin.setLength(0)
        candidates = emptyList()
        selected = 0
    }

    /** [candidate] capitalised like the typing. One capital letter is a capitalised word, not an
     * all-caps one: it is what a word's first keystroke after Shift looks like. */
    private fun inTypedCase(candidate: String): String {
        val typed = latin
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> candidate.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> candidate.replaceFirstChar { it.uppercaseChar() }
            else -> candidate
        }
    }

    private fun refresh(candidatesFor: (String) -> List<String>) {
        candidates = candidatesFor(typed.lowercase())
        selected = 0
        strip.firstOrNull()?.let(editor::setComposing)
    }
}
