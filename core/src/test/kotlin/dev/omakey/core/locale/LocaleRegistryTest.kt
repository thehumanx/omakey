package dev.omakey.core.locale

import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyRow
import dev.omakey.core.layout.KeyType
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.layout.withLanguageKeyInsteadOfEmoji
import dev.omakey.core.layout.withSpaceLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocaleRegistryTest {

    private val spanish = KeyboardLocale.EnUs.copy(
        id = "es_ES",
        displayName = "Spanish",
        nativeName = "Español",
        letterLayout = Layouts.QwertyEnUS.copy(id = "qwerty_es"),
        languageModel = ModelSource.File("/data/omakey/es/lm.bin"),
    )

    @Test
    fun `registering a language makes it and its layouts available`() {
        val registry = LocaleRegistry()
        registry.register(spanish)
        assertSame(spanish, registry["es_ES"])
        assertNotNull(registry.layouts["qwerty_es"])
        assertEquals(listOf("en_US", "es_ES"), registry.available.value.map { it.id })
    }

    @Test
    fun `a language with an invalid layout is refused and nothing changes`() {
        val registry = LocaleRegistry()
        val broken = spanish.copy(letterLayout = KeyboardLayout("broken", listOf(KeyRow(emptyList()))))
        try {
            registry.register(broken)
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(registry["es_ES"])
        assertNull(registry.layouts["broken"])
    }

    @Test
    fun `re-registering replaces, and bundled languages cannot be unregistered`() {
        val registry = LocaleRegistry()
        registry.register(spanish)
        val updated = spanish.copy(displayName = "Spanish (updated)")
        registry.register(updated)
        assertSame(updated, registry["es_ES"])
        assertEquals(2, registry.available.value.size)
        registry.unregister("es_ES")
        registry.unregister("en_US")
        assertEquals(listOf("en_US"), registry.available.value.map { it.id })
    }

    @Test
    fun `resolve drops unknown ids and never returns nothing`() {
        val registry = LocaleRegistry()
        assertEquals(listOf("en_US"), registry.resolve(listOf("xx_XX", "en_US")).map { it.id })
        assertEquals(listOf("en_US"), registry.resolve(listOf("xx_XX")).map { it.id })
    }

    /** A controller that just records switches, to exercise the interface's own [next]. */
    private class Recording(languages: List<KeyboardLocale>) : LocaleController {
        override val enabled: StateFlow<List<KeyboardLocale>> = MutableStateFlow(languages)
        private val _active = MutableStateFlow(languages.first())
        override val active: StateFlow<KeyboardLocale> = _active
        override fun onFieldStarted(hintLanguages: List<String>) = Unit
        private val choices = mutableMapOf<String, String>()
        override fun switchTo(id: String, remember: Boolean) {
            _active.value = enabled.value.first { it.id == id }.withLetterLayout(choices[id])
        }
        override fun chooseLayout(localeId: String, layoutId: String) {
            choices[localeId] = layoutId
            if (_active.value.id == localeId) _active.value = _active.value.withLetterLayout(layoutId)
        }
    }

    @Test
    fun `the globe key cycles through every layout of every language`() {
        val azerty = Layouts.QwertyEnUS.copy(id = "azerty_fr")
        val qwertyFr = Layouts.QwertyEnUS.copy(id = "qwerty_fr")
        val french = spanish.copy(id = "fr_FR", letterLayout = qwertyFr, letterLayoutChoices = listOf(azerty, qwertyFr))
        val controller = Recording(listOf(KeyboardLocale.EnUs, french, spanish))
        val stops = List(5) {
            controller.next()
            controller.active.value.let { "${it.id}/${it.letterLayout.id}" }
        }
        // French is entered at its first layout even though QWERTY was the one in use before.
        assertEquals(
            listOf("fr_FR/azerty_fr", "fr_FR/qwerty_fr", "es_ES/qwerty_es", "en_US/qwerty_en_us", "fr_FR/azerty_fr"),
            stops,
        )
    }

    @Test
    fun `next cycles through enabled languages and wraps`() {
        val nepali = spanish.copy(id = "ne_NP", nativeName = "नेपाली")
        val controller = Recording(listOf(KeyboardLocale.EnUs, spanish, nepali))
        controller.next(); assertEquals("es_ES", controller.active.value.id)
        controller.next(); assertEquals("ne_NP", controller.active.value.id)
        controller.next(); assertEquals("en_US", controller.active.value.id)
        FixedLocaleController().next() // single language: a no-op, not a crash
    }

    @Test
    fun `the spacebar names a language by its code unless the pack says otherwise`() {
        assertEquals("EN", KeyboardLocale.EnUs.spacebarName)
        assertEquals("ES", spanish.spacebarName)
        assertEquals("PT BR", spanish.copy(id = "pt_BR", shortLabel = "PT BR").spacebarName)
    }

    @Test
    fun `the spacebar label changes only the spacebar`() {
        val labelled = Layouts.QwertyEnUS.withSpaceLabel("English")
        val before = Layouts.QwertyEnUS.rows.last().keys
        val after = labelled.rows.last().keys
        assertEquals(before.map { it.code }, after.map { it.code })
        assertEquals(before.map { it.widthWeight }, after.map { it.widthWeight })
        assertEquals("English", after.single { it.code == SpecialKeyCode.SPACE }.label)
        assertEquals(Layouts.QwertyEnUS.rows.dropLast(1), labelled.rows.dropLast(1))
    }

    @Test
    fun `swapping puts the language key exactly where the emoji key was`() {
        val swapped = Layouts.QwertyEnUS.withLanguageKeyInsteadOfEmoji()
        val before = Layouts.QwertyEnUS.rows.last().keys
        val after = swapped.rows.last().keys
        val emojiIndex = before.indexOfFirst { it.code == SpecialKeyCode.EXTENSIONS }
        assertEquals(SpecialKeyCode.LANGUAGE, after[emojiIndex].code)
        assertEquals(before[emojiIndex].widthWeight, after[emojiIndex].widthWeight)
        assertTrue(after.none { it.code == SpecialKeyCode.EXTENSIONS })
        assertTrue(after[emojiIndex].keyType == KeyType.SPECIAL)
        assertEquals(emptyList<String>(), swapped.validate())
        // No emoji key (symbols pages): nothing to swap.
        assertEquals(Layouts.Symbols1, Layouts.Symbols1.withLanguageKeyInsteadOfEmoji())
    }
}
