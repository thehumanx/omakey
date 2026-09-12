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
fun isSensitiveField(info: EditorInfo?): Boolean {
    val editorInfo = info ?: return false
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
