package dev.omakey.core.layout

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.Script
import dev.omakey.core.pack.ProfileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hint drawn on a key ("Show secondary symbols") and the per-language digits on the shared
 * number row and symbols page — checked against every language pack's real layouts, not just
 * hand-made keys, since a pack is where a key with nothing sensible to show would come from.
 */
class SecondarySymbolAndDigitsTest {

    private fun key(label: String, vararg popup: String) = KeyDefinition(label, label.codePointAt(0), popupChars = popup.toList())

    @Test
    fun `the hint is the first digit or symbol, skipping accents`() {
        assertEquals("3", key("e", "è", "é", "ê", "ë", "3").secondarySymbol)
        assertEquals("@", key("ф", "@").secondarySymbol)
        assertEquals("?", key("ь", "ъ", "?").secondarySymbol)
    }

    @Test
    fun `a key whose popups are all letters shows its first letter`() {
        assertEquals("ъ", key("х", "ъ").secondarySymbol)
        assertEquals("ठ", key("ट", "ठ").secondarySymbol)
    }

    @Test
    fun `invisible format characters are never a hint`() {
        assertNull(key("्", "‌", "‍").secondarySymbol)
    }

    @Test
    fun `keys with nothing to show have no hint`() {
        assertNull(key("ж").secondarySymbol)
        assertNull(KeyDefinition("⇧", SpecialKeyCode.SHIFT, popupChars = listOf("1"), keyType = KeyType.SPECIAL).secondarySymbol)
    }

    @Test
    fun `western digits leave the layouts untouched`() {
        assertSame(Layouts.Symbols1, Layouts.Symbols1.withDigits(LanguageProfile.WESTERN_DIGITS))
        assertSame(Layouts.NumberRow, Layouts.NumberRow.withDigits(LanguageProfile.WESTERN_DIGITS))
    }

    @Test
    fun `devanagari digits replace the number row, western digits stay on long-press`() {
        val row = Layouts.NumberRow.withDigits(LanguageProfile.DEVANAGARI_DIGITS)
        assertEquals("१२३४५६७८९०".map { it.toString() }, row.keys.map { it.label })
        assertEquals("१", row.keys[0].committedText)
        assertEquals(listOf("1"), row.keys[0].popupChars)
    }

    @Test
    fun `the localized symbols page is still a valid layout with the same key widths`() {
        val localized = Layouts.Symbols1.withDigits(LanguageProfile.DEVANAGARI_DIGITS)
        assertEquals(emptyList<String>(), localized.validate())
        assertEquals(
            Layouts.Symbols1.rows.map { r -> r.keys.map { it.widthWeight } },
            localized.rows.map { r -> r.keys.map { it.widthWeight } },
        )
        assertEquals("०", localized.rows[0].keys.last().label)
    }

    @Test
    fun `digits come from the script unless the pack says otherwise`() {
        assertEquals(LanguageProfile.DEVANAGARI_DIGITS, ProfileSpec(Script.DEVANAGARI, hasCase = false).toProfile().digits)
        assertEquals(LanguageProfile.WESTERN_DIGITS, ProfileSpec(Script.CYRILLIC, hasCase = true).toProfile().digits)
        assertEquals(
            LanguageProfile.WESTERN_DIGITS,
            ProfileSpec(Script.DEVANAGARI, hasCase = false, digits = "0123456789").toProfile().digits,
        )
    }

    // ---- Every language pack's real layouts ----

    private val packLayouts: List<KeyboardLayout> by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "languages").isDirectory) dir = dir.parentFile
        File(dir ?: error("languages/ not found"), "languages")
            .walk().filter { it.isFile && it.parentFile.name == "layouts" && it.extension == "json" }
            .map { LayoutRepository.parse(it.readText()) }
            .toList()
            .also { assertTrue("no pack layouts found", it.isNotEmpty()) }
    }

    @Test
    fun `every pack layout is valid`() {
        for (layout in packLayouts) assertEquals(layout.id, emptyList<String>(), layout.validate())
    }

    @Test
    fun `every hint in every pack is visible text`() {
        for (layout in packLayouts) for (row in layout.rows) for (k in row.keys) {
            val hint = k.secondarySymbol ?: continue
            assertTrue("${layout.id} '${k.label}' hint '$hint'", hint.isNotBlank() && hint.any { Character.getType(it).toByte() != Character.FORMAT })
        }
    }

    @Test
    fun `every letter layout's top row hints a digit, so long-press reaches numbers in every language`() {
        val letterLayouts = packLayouts.filter { it.homeRow >= 0 && !it.id.endsWith("_shift") } + Layouts.QwertyEnUS
        for (layout in letterLayouts) {
            val hints = layout.rows[0].keys.mapNotNull { it.secondarySymbol }
            assertTrue("${layout.id} top-row hints $hints", hints.size >= 10 && hints.take(10).all { h -> h.single().isDigit() })
        }
    }

    @Test
    fun `nepali devanagari layout offers both digit forms on its top row`() {
        val layout = packLayouts.single { it.id == "devanagari_ne" }
        val first = layout.rows[0].keys[0]
        assertEquals("१", first.secondarySymbol)
        assertTrue(first.popupChars.containsAll(listOf("१", "1")))
    }
}
