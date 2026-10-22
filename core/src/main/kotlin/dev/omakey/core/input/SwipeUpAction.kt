package dev.omakey.core.input

/**
 * What a swipe up does to the word in focus. One rule (Fleksy's): **put back what was typed if
 * something else is showing; if what was typed is already showing, save it.**
 *
 * The cases it has to get right, all reported:
 *  - Typed "Bibek", nothing changed it: swipe up saves it.
 *  - Typed "bibek", swiped down to "bible": swipe up brings "bibek" back and does *not* save it —
 *    undoing a swipe is not a request to learn. A second swipe up saves.
 *  - Autocorrect changed "Bibek" to something else: swipe up brings "Bibek" back, again without
 *    saving; a second swipe up saves.
 *
 * Before this, reaching the first candidate and restoring the typed word were one step that also
 * saved, so undoing a correction taught the keyboard the word as a side effect.
 */
enum class SwipeUpAction {
    /** Several swipes down deep: go back one candidate. */
    STEP_BACK,

    /** A candidate (chosen by swiping or by autocorrect) is showing: put the typed word back. */
    RESTORE_TYPED,

    /** The typed word is what's showing: save it, or unsave a word the user taught. */
    SAVE,
    ;

    companion object {
        /**
         * @param cycleIndex index of the candidate on screen, or -1 when the typed word is.
         * @param hasWordInFocus whether there is a typed word to go back to (a correction is being
         *   tracked) — without one there is nothing to restore, only something to save.
         */
        fun decide(cycleIndex: Int, hasWordInFocus: Boolean): SwipeUpAction = when {
            !hasWordInFocus || cycleIndex < 0 -> SAVE
            cycleIndex == 0 -> RESTORE_TYPED
            else -> STEP_BACK
        }
    }
}
