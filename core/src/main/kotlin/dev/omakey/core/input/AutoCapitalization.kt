package dev.omakey.core.input

import android.text.InputType
import dev.omakey.core.locale.LanguageProfile

/**
 * When auto-capitalise turns Shift on, as one testable rule instead of a check written inline.
 *
 * The inline version only knew one case — "a space after . ! ?" — and a user report about "i"
 * prompted an audit that found the rest missing:
 *  - no capital after Enter, or at the start of a blank line;
 *  - no capital when a field opened, or the cursor was moved, to just after ". ";
 *  - a capital offered in email, URL and password fields, where it breaks what is typed;
 *  - fields that ask for every word or every letter capitalised (a name field, a postcode) were
 *    treated like prose.
 */
object AutoCapitalization {

    /** What the focused field allows, from its `EditorInfo.inputType`. */
    enum class FieldMode {
        /** Ordinary prose: capitalise sentence starts. */
        SENTENCES,

        /** The field asks for every word capitalised (names, titles). */
        WORDS,

        /** The field asks for every letter capitalised. */
        CHARACTERS,

        /** Never: email addresses, URLs, passwords, numbers. A capital there changes what the
         * text means, or makes a password wrong. */
        NONE,
    }

    fun fieldMode(inputType: Int): FieldMode {
        if (inputType == InputType.TYPE_NULL) return FieldMode.SENTENCES
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return FieldMode.NONE
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            -> return FieldMode.NONE
        }
        return when {
            inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> FieldMode.CHARACTERS
            inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> FieldMode.WORDS
            // No CAP_SENTENCES flag is treated as prose too, deliberately: plenty of ordinary text
            // fields never set it, and the user turned auto-capitalise on to get capitals.
            else -> FieldMode.SENTENCES
        }
    }

    /**
     * Whether the next letter typed after [textBefore] should be a capital.
     *
     * [textBefore] needs only the last few characters before the cursor; a dozen is plenty to see
     * past trailing spaces and a closing quote.
     */
    fun shouldCapitalize(textBefore: CharSequence, profile: LanguageProfile, mode: FieldMode): Boolean {
        if (!profile.hasCase) return false
        return when (mode) {
            FieldMode.NONE -> false
            FieldMode.CHARACTERS -> true
            FieldMode.WORDS -> textBefore.isEmpty() || textBefore.last().isWhitespace() || isSentenceStart(textBefore, profile)
            FieldMode.SENTENCES -> isSentenceStart(textBefore, profile)
        }
    }

    private fun isSentenceStart(textBefore: CharSequence, profile: LanguageProfile): Boolean {
        var end = textBefore.length
        // Opening punctuation already typed at a sentence start ("¿", "(", a quote) keeps it one:
        // the capital belongs to the first letter, not to the mark.
        while (end > 0 && textBefore[end - 1] in openers(profile)) end--
        if (end == 0) return true
        val last = textBefore[end - 1]
        if (last == '\n') return true
        // A letter or mark right before the cursor is someone editing mid-word; "hello." with no
        // space is someone editing the sentence end, not starting the next one.
        if (!last.isWhitespace()) return false
        while (end > 0 && textBefore[end - 1].isWhitespace() && textBefore[end - 1] != '\n') end--
        if (end == 0) return true
        var index = end - 1
        if (textBefore[index] == '\n') return true
        // "He said "Stop." Then…" — a closing quote or bracket after the full stop.
        while (index > 0 && textBefore[index] in CLOSERS) index--
        return textBefore[index] in profile.capitalizeAfter
    }

    private fun openers(profile: LanguageProfile): Set<Char> = profile.openingPunctuation + OPENERS

    private val OPENERS = setOf('(', '[', '"', '“', '«', '‘')
    private val CLOSERS = setOf(')', ']', '"', '”', '»', '’', '\'')

    /**
     * The properly capitalised form of a word the language always capitalises — English "i" — or
     * null. Only the bare word: "i'm", "i'll" and friends are typed as "i" then an apostrophe, so
     * the "i" is fixed when the apostrophe ends it, and the model's "i'm" is cased by
     * [capitalizedSuggestion].
     */
    fun alwaysCapitalized(word: String, profile: LanguageProfile): String? =
        profile.alwaysCapitalized[word.lowercase()]?.takeIf { it != word }

    /** [word] as a suggestion should show it: "i'm" from the model reads "I'm". Unchanged when the
     * language has no such words. */
    fun capitalizedSuggestion(word: String, profile: LanguageProfile): String {
        if (profile.alwaysCapitalized.isEmpty()) return word
        profile.alwaysCapitalized[word.lowercase()]?.let { return it }
        val apostrophe = word.indexOfFirst { it == '\'' || it == '’' }
        if (apostrophe <= 0) return word
        val head = profile.alwaysCapitalized[word.substring(0, apostrophe).lowercase()] ?: return word
        return head + word.substring(apostrophe)
    }
}
