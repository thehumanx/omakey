package dev.omakey.core.input

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Whether the focused field is one omakey must not remember anything from.
 *
 * This is the single most security-relevant predicate in the keyboard: it decides whether typing is
 * learned into the personal dictionary, and (via `IncognitoPreferences.onFieldChanged`) whether
 * clipboard history records anything either. It lived as a private method inside
 * `KeyboardViewModel`, which needs a `Context` for every preference dependency and therefore cannot
 * be constructed in a plain JVM test — so the one function most deserving of tests was the one that
 * could not have any. It is pure, it depends only on its argument, and it is now here.
 *
 * Three separate reasons a field counts as sensitive:
 *
 *  - `IME_FLAG_NO_PERSONALIZED_LEARNING` — the platform's explicit "don't remember this", honoured
 *    directly and regardless of input type.
 *  - a password variation, in either the text or the numeric class. `VISIBLE_PASSWORD` counts:
 *    the characters being legible on screen says nothing about whether the value is a secret.
 *  - `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, which is a field asking not to be autocompleted at all.
 *
 * **Variations live in the low bits of `inputType` and must be masked before comparison.** Testing
 * `inputType and VARIATION == VARIATION` without first masking matches unrelated fields, which is
 * how this kind of check silently fails open.
 */
fun isSensitiveField(info: EditorInfo?, ownPackage: String? = null): Boolean {
    val editorInfo = info ?: return false
    // omakey's own screens (the snippet form above all) are never learned from: a secret is typed
    // into a plain text field there, before the Secret switch is reached, and was being learned
    // into the dictionary in plain text. Nothing typed in omakey's settings is worth learning.
    if (ownPackage != null && editorInfo.packageName == ownPackage) return true
    if ((editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true

    val inputType = editorInfo.inputType
    val classType = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION

    if (classType == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
        return true
    }
    if (classType == InputType.TYPE_CLASS_TEXT) {
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
    }
    return false
}

/**
 * Whether the focused field holds a secret: a password variation, text or numeric. Narrower than
 * [isSensitiveField] on purpose: snippet short codes are blocked only here. "Don't learn" fields
 * (incognito tabs, many messengers) and omakey's own Try screen still offer the user's snippets,
 * since offering what the user saved learns nothing.
 */
fun isPasswordField(info: EditorInfo?): Boolean {
    val inputType = info?.inputType ?: return false
    val classType = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (classType) {
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else -> false
    }
}
