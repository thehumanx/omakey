package dev.omakey.core.locale

import dev.omakey.core.layout.KeyDefinition
import dev.omakey.core.layout.KeyRow
import dev.omakey.core.layout.KeyType
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.SpecialKeyCode
import dev.omakey.core.layout.withLanguageKey
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
        override fun switchTo(id: String, remember: Boolean) {
            _active.value = enabled.value.first { it.id == id }
        }
        override fun onFieldStarted(hintLanguages: List<String>) = Unit
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
    fun `the language key takes its width from the spacebar and nothing else moves`() {
        val decorated = Layouts.QwertyEnUS.withLanguageKey("English")
        val before = Layouts.QwertyEnUS.rows.last().keys
        val after = decorated.rows.last().keys
        assertEquals(before.sumOf { it.widthWeight.toDouble() }, after.sumOf { it.widthWeight.toDouble() }, 1e-6)
        val languageIndex = after.indexOfFirst { it.code == SpecialKeyCode.LANGUAGE }
        assertEquals(SpecialKeyCode.SPACE, after[languageIndex + 1].code)
        assertEquals("English", after[languageIndex + 1].label)
        assertEquals(Layouts.QwertyEnUS.rows.dropLast(1), decorated.rows.dropLast(1))
        assertEquals(emptyList<String>(), decorated.validate())
        // Idempotent: decorating twice doesn't add a second key.
        assertEquals(decorated, decorated.withLanguageKey("English"))
        assertTrue(after.single { it.code == SpecialKeyCode.LANGUAGE }.keyType == KeyType.SPECIAL)
        // Symbols keep their space row untouched only if asked; the function itself is general.
        assertEquals(1, KeyboardLayout("x", listOf(KeyRow(listOf(KeyDefinition(" ", SpecialKeyCode.SPACE, widthWeight = 4f)))))
            .withLanguageKey("X").rows.single().keys.count { it.code == SpecialKeyCode.LANGUAGE })
    }
}
