package dev.omakey.core.input

/**
 * "Space after punctuation" (`AutocorrectSettings.autoSpaceAfterPunctuation`): typing one of
 * [SPACED] adds a space after it, and the next keystroke may take that space back.
 *
 * The space is provisional — the way Fleksy and SwiftKey do it, not a dumb "always add one":
 *  - **Space typed next** is swallowed, so someone who already types their own space never gets
 *    two.
 *  - **More punctuation or a closing bracket/quote typed next** ("?!", "...", "word.)") goes before
 *    the space, not after it.
 *  - **A digit typed next, when the mark sits between digits** ("3.14", "1,000", "10:30") goes
 *    straight after the mark, so numbers aren't broken apart.
 *
 * Anything else (a letter, an opening quote, a symbol) keeps the space.
 *
 * Pure rules over the text, so the keyboard's view model only has to apply them.
 */
object PunctuationSpacing {
    /** Marks that get a space after them. */
    val SPACED: Set<Char> = setOf('.', ',', '!', '?', ';', ':', '…')

    /** Typed straight after a mark, these join it before the space: unambiguous closers only.
     * Straight quotes (' ") are left out — after ". " one is as likely to open a quote as to close
     * one, and gluing an opening quote to the sentence before is the worse mistake. */
    private val CLOSERS: Set<Char> = setOf(')', ']', '}', '”', '’', '»', '›')

    /** Marks that sit inside numbers: 3.14, 1,000, 10:30. */
    private val NUMERIC: Set<Char> = setOf('.', ',', ':')

    /** Whether typing [text] adds a space after it, given [textAfter] — what follows the cursor.
     * Only at the end of a line: typed into the middle of text, the space is the user's business. */
    fun addsSpace(text: String, textAfter: String): Boolean =
        text.length == 1 && text[0] in SPACED && (textAfter.isEmpty() || textAfter[0] == '\n')

    /**
     * Whether [next] — about to be typed right after a space this added — should replace that space
     * rather than follow it. [textBefore] is what precedes the cursor and ends with the mark and its
     * space; it decides the digit case, which needs to see the character before the mark.
     */
    fun replacesSpace(next: String, textBefore: String): Boolean {
        val char = next.singleOrNull() ?: return false
        if (char in SPACED || char in CLOSERS) return true
        if (!char.isDigit()) return false
        // textBefore ends "<digit><mark> ".
        if (textBefore.length < 3 || !textBefore.endsWith(' ')) return false
        val mark = textBefore[textBefore.length - 2]
        return mark in NUMERIC && textBefore[textBefore.length - 3].isDigit()
    }

    /** Whether [next] keeps the space it replaced after it: punctuation and closers end the same
     * sentence or clause the mark did ("?!", "word.)"); a digit continues a number. */
    fun respacesAfter(next: String): Boolean = next.singleOrNull()?.let { it in SPACED || it in CLOSERS } == true
}
