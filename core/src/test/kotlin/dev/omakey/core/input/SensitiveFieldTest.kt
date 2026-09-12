package dev.omakey.core.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The predicate that decides whether typing is learned and whether the clipboard records anything.
 * It was previously private to `KeyboardViewModel` and therefore untestable; these are the cases
 * that have to keep holding.
 */
class SensitiveFieldTest {

    private fun editorInfo(inputType: Int = 0, imeOptions: Int = 0) = EditorInfo().apply {
        this.inputType = inputType
        this.imeOptions = imeOptions
    }

    @Test
    fun `a plain text field is not sensitive`() {
        assertFalse(isSensitiveField(editorInfo(InputType.TYPE_CLASS_TEXT)))
    }

    @Test
    fun `a null EditorInfo is not sensitive`() {
        // Fields can arrive without one; failing closed here would disable learning everywhere.
        assertFalse(isSensitiveField(null))
    }

    @Test
    fun `text password fields are sensitive`() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            // Legible on screen says nothing about whether the value is a secret.
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )) {
            assertTrue(
                "variation $variation should be sensitive",
                isSensitiveField(editorInfo(InputType.TYPE_CLASS_TEXT or variation)),
            )
        }
    }

    @Test
    fun `numeric PIN fields are sensitive`() {
        val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(isSensitiveField(editorInfo(pin)))
    }

    @Test
    fun `an ordinary number field is not sensitive`() {
        assertFalse(isSensitiveField(editorInfo(InputType.TYPE_CLASS_NUMBER)))
    }

    @Test
    fun `no-suggestions text fields are sensitive`() {
        val noSuggestions = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(isSensitiveField(editorInfo(noSuggestions)))
    }

    @Test
    fun `NO_PERSONALIZED_LEARNING is honoured whatever the input type`() {
        // The platform's explicit "don't remember this", which must win regardless of field type.
        assertTrue(
            isSensitiveField(
                editorInfo(
                    inputType = InputType.TYPE_CLASS_TEXT,
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
                ),
            ),
        )
    }

    @Test
    fun `variations are masked before comparison`() {
        // The failure mode this guards: `inputType and VARIATION == VARIATION` without masking.
        // TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS (0xd0) shares set bits with
        // TYPE_TEXT_VARIATION_PASSWORD (0x80), so an unmasked check reads an email field as a
        // password — which fails *closed* and would be noticed. The dangerous direction is the
        // reverse, so both are pinned here.
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        assertFalse(isSensitiveField(editorInfo(email)))

        val emailSubject = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT
        assertFalse(isSensitiveField(editorInfo(emailSubject)))
    }

    @Test
    fun `multiline and autocorrect flags do not confuse the variation check`() {
        // Flags sit in high bits and must not leak into the variation comparison.
        val password = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        assertTrue(isSensitiveField(editorInfo(password)))
    }
}
