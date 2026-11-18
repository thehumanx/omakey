package dev.omakey.core.predict.spatial

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.OrthographicConfusions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spelling confusions that are not key slips. Measured on Russian (synthetic orthographic errors,
 * see `RussianOrthography`): й typed as и or о as а is a far-apart key pair, so it was priced as an
 * unlikely slip and a nearby wrong word won ("каждыи" → "каждым").
 */
class ChannelConfusionTest {

    private val plain = ChannelModel()
    private val russian = ChannelModel().forLanguage(
        KeyboardGeometry.QWERTY,
        equivalentGroups = emptyList(),
        confusableGroups = listOf("йи", "оа"),
        omittableLetters = "ь",
    )

    @Test
    fun `a confusable pair costs the confusable price, not the distance between its keys`() {
        assertEquals(ChannelModel.CONFUSABLE_COST, russian.substitution('и', 'й'), 0.001f)
        assertEquals(ChannelModel.CONFUSABLE_COST, russian.substitution('а', 'о'), 0.001f)
    }

    @Test
    fun `letters outside a group are priced as before`() {
        assertEquals(plain.substitution('q', 'm'), russian.substitution('q', 'm'), 0.001f)
    }

    @Test
    fun `an omittable letter is cheap to have left out, others are not`() {
        assertEquals(ChannelModel.OMISSION_COST, russian.deletion('ь'), 0.001f)
        assertEquals(ChannelModel.DELETION_COST, russian.deletion('к'), 0.001f)
        assertTrue(ChannelModel.OMISSION_COST < ChannelModel.DELETION_COST)
    }

    @Test
    fun `russian gets its confusions without a pack update`() {
        val profile = OrthographicConfusions.applyDefaults(LanguageProfile.English, "ru_RU")
        assertTrue(profile.confusableLetters.any { 'й' in it && 'и' in it })
        assertTrue('ь' in profile.omittableLetters)
    }

    @Test
    fun `a pack that declares its own confusions keeps them`() {
        val declared = LanguageProfile.English.withOrthography(listOf("ab"), emptySet())
        assertEquals(listOf("ab"), OrthographicConfusions.applyDefaults(declared, "ru_RU").confusableLetters)
    }

    @Test
    fun `english has none`() {
        val profile = OrthographicConfusions.applyDefaults(LanguageProfile.English, "en_US")
        assertTrue(profile.confusableLetters.isEmpty() && profile.omittableLetters.isEmpty())
    }
}
