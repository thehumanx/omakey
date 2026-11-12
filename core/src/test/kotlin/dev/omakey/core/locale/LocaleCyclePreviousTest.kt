package dev.omakey.core.locale

import dev.omakey.core.layout.Layouts
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** [LocaleController.previous] is [LocaleController.next] run backwards, layouts included. */
class LocaleCyclePreviousTest {

    private val english = KeyboardLocale.EnUs
    private val azerty = Layouts.QwertyEnUS.copy(id = "azerty")
    private val qwertyFr = Layouts.QwertyEnUS.copy(id = "qwerty_fr")
    private val french = KeyboardLocale.EnUs.copy(id = "fr_FR", letterLayout = azerty, letterLayoutChoices = listOf(azerty, qwertyFr))
    private val spanish = KeyboardLocale.EnUs.copy(id = "es_ES")

    private class Fake(languages: List<KeyboardLocale>) : LocaleController {
        private val all = languages.associateBy { it.id }.toMutableMap()
        override val enabled = MutableStateFlow(languages)
        override val active = MutableStateFlow(languages.first())
        override fun switchTo(id: String, remember: Boolean) { active.value = all.getValue(id) }
        override fun onFieldStarted(hintLanguages: List<String>) = Unit
        override fun chooseLayout(localeId: String, layoutId: String) {
            all[localeId] = all.getValue(localeId).withLetterLayout(layoutId)
            if (active.value.id == localeId) active.value = all.getValue(localeId)
        }
        fun position() = "${active.value.id}/${active.value.letterLayout.id}"
    }

    private fun cycle(controller: Fake, steps: Int, move: Fake.() -> Unit) =
        (1..steps).map { controller.move(); controller.position() }

    @Test
    fun `previous visits every stop next visits, in reverse`() {
        val forward = cycle(Fake(listOf(english, french, spanish)), 4) { next() }
        val backward = cycle(Fake(listOf(english, french, spanish)), 4) { previous() }
        // Both run a full loop of four stops and end back on English.
        assertEquals(forward.last(), backward.last())
        assertEquals(forward.dropLast(1).reversed(), backward.dropLast(1))
    }

    @Test
    fun `previous enters a language at its last layout`() {
        val controller = Fake(listOf(english, french))
        controller.previous()
        assertEquals("fr_FR/qwerty_fr", controller.position())
        controller.previous()
        assertEquals("fr_FR/azerty", controller.position())
        controller.previous()
        assertEquals(english.id + "/" + english.letterLayout.id, controller.position())
    }

    @Test
    fun `next then previous comes back`() {
        val controller = Fake(listOf(english, french, spanish))
        val start = controller.position()
        controller.next()
        controller.previous()
        assertEquals(start, controller.position())
    }
}
