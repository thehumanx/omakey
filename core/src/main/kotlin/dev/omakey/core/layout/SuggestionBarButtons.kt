package dev.omakey.core.layout

/**
 * Which buttons the suggestion bar draws around the suggestions. One rule shared by the bar itself
 * and the autofill request, which sizes password-manager chips to the room the buttons leave: if
 * the two disagree, the chips come out the wrong width.
 */
object SuggestionBarButtons {

    /** A button at the right end of the bar. */
    enum class Trailing { LANGUAGE, EMOJI }

    /**
     * The right-end buttons, left to right. The emoji button is there whenever the key left of the
     * spacebar was given to something else ([emojiKeyInBar]), so emoji is always one tap away.
     * The language button needs two or more languages, and is left out when the language key is
     * on the keyboard already, or when the spacebar swipe switches language
     * ([swipeSpacebarForLanguage]): two controls for one job. That only holds while swipes work at
     * all ([gesturesActive]): accessible mode turns them off, and hiding the button there would
     * leave TalkBack users no one-tap way to change language.
     */
    fun trailing(
        languageCount: Int,
        emojiSlotKey: String,
        swipeSpacebarForLanguage: Boolean,
        gesturesActive: Boolean = true,
    ): List<Trailing> {
        val languageOnKeyboard = emojiSlotKey == EMOJI_SLOT_LANGUAGE && languageCount >= 2
        val language = languageCount >= 2 && !languageOnKeyboard && !(swipeSpacebarForLanguage && gesturesActive)
        return listOfNotNull(
            Trailing.EMOJI.takeIf { emojiKeyInBar(emojiSlotKey, languageCount) },
            Trailing.LANGUAGE.takeIf { language },
        )
    }

    /** Quick access is always there; add the trailing buttons. */
    fun count(languageCount: Int, emojiSlotKey: String, swipeSpacebarForLanguage: Boolean, gesturesActive: Boolean = true): Int =
        1 + trailing(languageCount, emojiSlotKey, swipeSpacebarForLanguage, gesturesActive).size
}
