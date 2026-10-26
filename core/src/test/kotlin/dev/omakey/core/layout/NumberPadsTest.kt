package dev.omakey.core.layout

import android.text.InputType
import dev.omakey.core.locale.LanguageProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The number pads: which field opens which, and that backspace and enter land exactly where the
 * letter layout has them — checked against every bundled and pack letter layout, since ЙЦУКЕН's
 * backspace is a different width from QWERTY's and the pad has to follow it.
 */
class NumberPadsTest {

    private val on = LayoutSettings()

    @Test
    fun `number, phone and date fields each get their own pad`() {
        assertEquals(NumberPadKind.NUMBER, NumberPads.kindFor(InputType.TYPE_CLASS_NUMBER, on))
        assertEquals(
            NumberPadKind.NUMBER,
            NumberPads.kindFor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED, on),
        )
        // A numeric PIN is still a number field; incognito is decided elsewhere.
        assertEquals(NumberPadKind.NUMBER, NumberPads.kindFor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, on))
        assertEquals(NumberPadKind.PHONE, NumberPads.kindFor(InputType.TYPE_CLASS_PHONE, on))
        assertEquals(NumberPadKind.DATETIME, NumberPads.kindFor(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE, on))
    }

    @Test
    fun `text fields and fields with no type keep the letters`() {
        assertNull(NumberPads.kindFor(InputType.TYPE_CLASS_TEXT, on))
        assertNull(NumberPads.kindFor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, on))
        assertNull(NumberPads.kindFor(InputType.TYPE_NULL, on))
    }

    @Test
    fun `the settings turn the pad off, as a whole or per kind of field`() {
        val off = LayoutSettings(numberPad = false)
        for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            assertNull(NumberPads.kindFor(type, off))
        }
        val noPhone = LayoutSettings(numberPadPhone = false)
        assertNull(NumberPads.kindFor(InputType.TYPE_CLASS_PHONE, noPhone))
        assertEquals(NumberPadKind.NUMBER, NumberPads.kindFor(InputType.TYPE_CLASS_NUMBER, noPhone))
        assertNull(NumberPads.kindFor(InputType.TYPE_CLASS_NUMBER, LayoutSettings(numberPadNumber = false)))
        assertNull(NumberPads.kindFor(InputType.TYPE_CLASS_DATETIME, LayoutSettings(numberPadDateTime = false)))
    }

    @Test
    fun `every pad is a valid layout with no way to letters`() {
        for (kind in NumberPadKind.entries) {
            val pad = NumberPads.build(kind, Layouts.QwertyEnUS, LanguageProfile.WESTERN_DIGITS)
            assertEquals(kind.name, emptyList<String>(), pad.validate())
            assertTrue(pad.id in NumberPads.IDS)
            val codes = pad.rows.flatMap { it.keys }.map { it.code }
            assertTrue(kind.name, SpecialKeyCode.LETTERS !in codes && SpecialKeyCode.SYMBOLS !in codes)
            assertEquals("1234567890".toList(), pad.rows.flatMap { it.keys }.mapNotNull { k -> k.label.singleOrNull()?.takeIf { it.isDigit() } })
        }
    }

    @Test
    fun `backspace sits where every letter layout has it, enter at the end of the last row`() {
        for (letters in listOf(Layouts.QwertyEnUS) + packLayouts) for (kind in NumberPadKind.entries) {
            val pad = NumberPads.build(kind, letters, LanguageProfile.WESTERN_DIGITS)
            val where = "${letters.id} ${kind.name}"
            assertSameEnd(where, letters.rows[letters.rows.size - 2], pad.rows[pad.rows.size - 2], SpecialKeyCode.BACKSPACE)
            assertEquals(where, SpecialKeyCode.ENTER, pad.rows.last().keys.last().code)
        }
    }

    @Test
    fun `every row has the same widths, so all five columns line up top to bottom`() {
        for (letters in listOf(Layouts.QwertyEnUS) + packLayouts) {
            val pad = NumberPads.build(NumberPadKind.NUMBER, letters, LanguageProfile.WESTERN_DIGITS)
            val widths = pad.rows.first().keys.map { it.widthWeight }
            assertEquals(1f, widths.sum(), 1e-4f)
            assertEquals(widths.first(), widths.last(), 1e-6f)
            for (row in pad.rows) assertEquals(letters.id, widths, row.keys.map { it.widthWeight })
        }
    }

    @Test
    fun `a letter layout without backspace last falls back to qwerty's widths`() {
        val odd = Layouts.QwertyEnUS.copy(rows = Layouts.QwertyEnUS.rows.map { row -> KeyRow(row.keys.reversed()) })
        val pad = NumberPads.build(NumberPadKind.NUMBER, odd, LanguageProfile.WESTERN_DIGITS)
        assertEquals(0.15f, pad.rows[2].keys.last().widthWeight, 1e-4f)
        assertEquals(0.15f, pad.rows[3].keys.last().widthWeight, 1e-4f)
    }

    @Test
    fun `the phone pad carries letter hints and a plus on zero`() {
        val pad = NumberPads.build(NumberPadKind.PHONE, Layouts.QwertyEnUS, LanguageProfile.WESTERN_DIGITS)
        val keys = pad.rows.flatMap { it.keys }.associateBy { it.label }
        assertEquals("ABC", keys.getValue("2").secondarySymbol)
        assertEquals("WXYZ", keys.getValue("9").secondarySymbol)
        assertNull(keys.getValue("1").secondarySymbol)
        assertEquals(listOf("+"), keys.getValue("0").popupChars)
        assertEquals("+", keys.getValue("0").secondarySymbol)
        // The letters are a label only: 2 still types 2.
        assertEquals("2", keys.getValue("2").committedText)
        // Other pads draw no letters.
        val number = NumberPads.build(NumberPadKind.NUMBER, Layouts.QwertyEnUS, LanguageProfile.WESTERN_DIGITS)
        assertTrue(number.rows.flatMap { it.keys }.filter { it.label.single().isDigit() }.all { it.secondarySymbol == null })
    }

    @Test
    fun `the keys type 0-9 in every language, the language's own digits on long-press`() {
        for (kind in NumberPadKind.entries) {
            val pad = NumberPads.build(kind, Layouts.QwertyEnUS, LanguageProfile.DEVANAGARI_DIGITS)
            val keys = pad.rows.flatMap { it.keys }.associateBy { it.label }
            assertEquals(kind.name, "1", keys.getValue("1").committedText)
            assertTrue(kind.name, "१" !in keys)
            assertEquals(kind.name, listOf("१"), keys.getValue("1").popupChars)
            assertTrue(kind.name, "०" in keys.getValue("0").popupChars)
            assertEquals(kind.name, emptyList<String>(), pad.validate())
        }
        // Western-digit languages get no long-press digits at all.
        val western = NumberPads.build(NumberPadKind.NUMBER, Layouts.QwertyEnUS, LanguageProfile.WESTERN_DIGITS)
        assertEquals(emptyList<String>(), western.rows.flatMap { it.keys }.first { it.label == "5" }.popupChars)
    }

    @Test
    fun `every language's own letter layouts and digits give a valid pad`() {
        for (letters in packLayouts) for (digits in listOf(LanguageProfile.WESTERN_DIGITS, LanguageProfile.DEVANAGARI_DIGITS)) {
            for (kind in NumberPadKind.entries) {
                assertEquals("${letters.id} ${kind.name}", emptyList<String>(), NumberPads.build(kind, letters, digits).validate())
            }
        }
    }

    private fun assertSameEnd(where: String, lettersRow: KeyRow, padRow: KeyRow, code: Int) {
        val letterKey = lettersRow.keys.last()
        assertEquals(where, code, letterKey.code)
        assertEquals(where, code, padRow.keys.last().code)
        val letterShare = letterKey.widthWeight / lettersRow.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
        val padShare = padRow.keys.last().widthWeight / padRow.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
        assertEquals(where, letterShare, padShare, 1e-4f)
    }

    private val packLayouts: List<KeyboardLayout> by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "languages").isDirectory) dir = dir.parentFile
        File(dir ?: error("languages/ not found"), "languages")
            .walk().filter { it.isFile && it.parentFile.name == "layouts" && it.extension == "json" }
            .map { LayoutRepository.parse(it.readText()) }
            .toList()
            .also { assertTrue("no pack layouts found", it.isNotEmpty()) }
    }
}
