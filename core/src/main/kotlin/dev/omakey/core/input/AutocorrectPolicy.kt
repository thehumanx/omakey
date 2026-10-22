package dev.omakey.core.input

import android.text.InputType

/**
 * Where the keyboard may change a word without asking.
 *
 * Suggestions in the strip are always offered; this only governs the *silent* fix at a word
 * boundary. Two rules, both from a user report (GitHub issue #9: "whenever I type a word and press
 * search, it autocorrects"):
 *
 *  - **Never on submit.** Search, Go, Send and Done send the text the moment the key is pressed, so
 *    a correction applied then can't be seen or undone before it's gone — and search queries are
 *    exactly where names and terms the dictionary doesn't know turn up. The caller skips the
 *    silent fix when Enter performs an editor action; see `KeyboardViewModel.onEnter`.
 *  - **Never in fields that say not to** ([fieldAllows]): web addresses, email addresses,
 *    passwords, numbers, and any field flagged "no suggestions". Changing a word there breaks it.
 */
object AutocorrectPolicy {

    fun fieldAllows(inputType: Int): Boolean {
        if (inputType == InputType.TYPE_NULL) return true
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> false
            else -> true
        }
    }
}
