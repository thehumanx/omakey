package dev.omakey.core.locale

import dev.omakey.core.layout.Layouts
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LayoutToggleTest {

    private val jcuken = Layouts.QwertyEnUS.copy(id = "jcuken")
    private val phonetic = Layouts.QwertyEnUS.copy(id = "phonetic")
    private val translit = Layouts.QwertyEnUS.copy(id = "translit")
    private val russian = KeyboardLocale.EnUs.copy(
        id = "ru_RU",
        letterLayout = jcuken,
        letterLayoutChoices = listOf(jcuken, phonetic, translit),
    )

    @Test
    fun `a switched-off layout leaves the choices`() {
        val filtered = russian.withoutLayouts(setOf("phonetic"))
        assertEquals(listOf("jcuken", "translit"), filtered.letterLayoutChoices.map { it.id })
        assertEquals("jcuken", filtered.letterLayout.id)
    }

    @Test
    fun `switching off the current layout moves to the first one left`() {
        val filtered = russian.withoutLayouts(setOf("jcuken"))
        assertEquals("phonetic", filtered.letterLayout.id)
    }

    @Test
    fun `one layout always stays`() {
        val filtered = russian.withoutLayouts(setOf("jcuken", "phonetic", "translit"))
        assertEquals(listOf("jcuken"), filtered.letterLayoutChoices.map { it.id })
    }

    @Test
    fun `nothing switched off changes nothing`() {
        assertSame(russian, russian.withoutLayouts(emptySet()))
    }

    @Test
    fun `a chosen layout that was switched off is not chosen`() {
        val filtered = russian.withoutLayouts(setOf("translit")).withLetterLayout("translit")
        assertEquals("jcuken", filtered.letterLayout.id)
    }

    @Test
    fun `the globe key skips switched-off layouts`() {
        val controller = object : LocaleController {
            override val active = MutableStateFlow(russian.withoutLayouts(setOf("phonetic")))
            override val enabled = MutableStateFlow(listOf(active.value))
            override fun switchTo(id: String, remember: Boolean) = Unit
            override fun onFieldStarted(hintLanguages: List<String>) = Unit
            override fun chooseLayout(localeId: String, layoutId: String) {
                active.value = active.value.withLetterLayout(layoutId)
            }
        }
        controller.next()
        assertEquals("translit", controller.active.value.letterLayout.id)
        controller.next()
        assertEquals("jcuken", controller.active.value.letterLayout.id)
    }
}
