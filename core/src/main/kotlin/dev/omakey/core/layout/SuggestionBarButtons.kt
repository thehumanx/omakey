package dev.omakey.core.layout

/**
 * Which buttons the suggestion bar draws around the suggestions. One rule shared by the bar itself
 * and the autofill request, which sizes password-manager chips to the room the buttons leave: if
 * the two disagree, the chips come out the wrong width.
 */
object SuggestionBarButtons {

    /** What sits at the right end of the bar. */
    enum class Trailing { NONE, LANGUAGE, EMOJI }

    /**
     * With one language there is no language button, and the emoji key stays in the bottom row
     * whichever way round [swapEmojiAndLanguage] is, so nothing goes here. Swapped, the emoji
     * button takes the language button's place. Otherwise the language button, unless the
     * spacebar swipe switches language ([swipeSpacebarForLanguage]): two controls for one job.
     * That only holds while swipes work at all ([gesturesActive]): accessible mode turns them off,
     * and hiding the button there would leave TalkBack users no one-tap way to change language.
     */
    fun trailing(
        languageCount: Int,
        swapEmojiAndLanguage: Boolean,
        swipeSpacebarForLanguage: Boolean,
        gesturesActive: Boolean = true,
    ): Trailing = when {
        languageCount < 2 -> Trailing.NONE
        swapEmojiAndLanguage -> Trailing.EMOJI
        swipeSpacebarForLanguage && gesturesActive -> Trailing.NONE
        else -> Trailing.LANGUAGE
    }

    /** Quick access is always there; add one for the trailing button when it is drawn. */
    fun count(languageCount: Int, swapEmojiAndLanguage: Boolean, swipeSpacebarForLanguage: Boolean, gesturesActive: Boolean = true): Int =
        if (trailing(languageCount, swapEmojiAndLanguage, swipeSpacebarForLanguage, gesturesActive) == Trailing.NONE) 1 else 2
}
