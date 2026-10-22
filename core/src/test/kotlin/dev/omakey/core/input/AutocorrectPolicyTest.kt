package dev.omakey.core.input

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocorrectPolicyTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun `ordinary text fields allow autocorrect`() {
        assertTrue(AutocorrectPolicy.fieldAllows(text))
        assertTrue(AutocorrectPolicy.fieldAllows(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(AutocorrectPolicy.fieldAllows(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertTrue("unknown field type", AutocorrectPolicy.fieldAllows(InputType.TYPE_NULL))
    }

    @Test
    fun `fields where a changed word breaks it do not`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ).forEach { assertFalse("variation $it", AutocorrectPolicy.fieldAllows(text or it)) }
        assertFalse("no suggestions", AutocorrectPolicy.fieldAllows(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse("numbers", AutocorrectPolicy.fieldAllows(InputType.TYPE_CLASS_NUMBER))
        assertFalse("phone", AutocorrectPolicy.fieldAllows(InputType.TYPE_CLASS_PHONE))
    }
}
