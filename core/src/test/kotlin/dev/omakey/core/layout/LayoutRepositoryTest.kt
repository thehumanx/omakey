package dev.omakey.core.layout

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutRepositoryTest {

    private fun key(label: String, code: Int = label.first().code, text: String? = null) =
        KeyDefinition(label, code, text = text)

    private val space = KeyDefinition(" ", SpecialKeyCode.SPACE, keyType = KeyType.SPECIAL)
    private val backspace = KeyDefinition("⌫", SpecialKeyCode.BACKSPACE, keyType = KeyType.SPECIAL)
    private val shift = KeyDefinition("⇧", SpecialKeyCode.SHIFT, keyType = KeyType.SPECIAL)

    /** A two-layer Devanagari layout of the shape a Nepali pack will ship. */
    private val base = KeyboardLayout(
        id = "test_ne",
        homeRow = 0,
        shiftLayoutId = "test_ne_shift",
        rows = listOf(
            KeyRow(listOf(key("क"), key("ि"), key("क्ष", code = 0xE000, text = "क्ष"))),
            KeyRow(listOf(shift, space, backspace)),
        ),
    )
    private val shiftLayer = base.copy(
        id = "test_ne_shift",
        shiftLayoutId = null,
        rows = listOf(KeyRow(listOf(key("ख"), key("ी"))), base.rows[1]),
    )

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `every bundled layout is valid`() {
        for (layout in Layouts.all) assertEquals(layout.id, emptyList<String>(), layout.validate())
    }

    @Test
    fun `layouts round-trip through JSON`() {
        val json = Json.encodeToString(KeyboardLayout.serializer(), Layouts.QwertyEnUS)
        assertEquals(Layouts.QwertyEnUS, LayoutRepository.parse(json))
    }

    @Test
    fun `unknown JSON fields are ignored, so older apps can read newer packs`() {
        val json = Json.encodeToString(KeyboardLayout.serializer(), base)
            .replaceFirst("{", "{\"futureField\":42,")
        assertEquals(base, LayoutRepository.parse(json))
    }

    @Test
    fun `a multi-code-point key commits its whole text`() {
        assertEquals("क्ष", base.keyForCode(0xE000)?.committedText)
        assertEquals("क", base.keyForCode('क'.code)?.committedText)
        assertNull(base.keyForCode(0xE001))
    }

    @Test
    fun `a base layout and its shift layer register together`() {
        val repository = LayoutRepository()
        repository.registerAll(listOf(base, shiftLayer))
        assertSame(shiftLayer, repository.shiftLayerOf(repository["test_ne"]!!))
        assertNull(repository.shiftLayerOf(Layouts.QwertyEnUS))
    }

    @Test
    fun `a dangling shift layer is rejected and nothing is registered`() {
        val repository = LayoutRepository()
        expectRejected { repository.register(base) }
        assertNull(repository["test_ne"])
    }

    @Test
    fun `malformed and invalid layouts are rejected with one exception type`() {
        expectRejected { LayoutRepository.parse("{ not json") }
        expectRejected { LayoutRepository.parse("""{"id":"x","rows":[]}""") }

        val problems = { layout: KeyboardLayout -> layout.validate() }
        val duplicate = base.copy(rows = listOf(KeyRow(listOf(key("क"), key("क"))), base.rows[1]))
        assertTrue(problems(duplicate).any { "duplicate code" in it })
        val noSpace = base.copy(rows = listOf(base.rows[0], KeyRow(listOf(backspace))))
        assertTrue(problems(noSpace).any { "space" in it })
        val zeroWidth = base.copy(rows = listOf(KeyRow(listOf(key("क").copy(widthWeight = 0f))), base.rows[1]))
        assertTrue(problems(zeroWidth).any { "widthWeight" in it })
        val badHomeRow = base.copy(homeRow = 5)
        assertTrue(problems(badHomeRow).any { "homeRow" in it })
        val codeWithoutText = base.copy(rows = listOf(KeyRow(listOf(key("?", code = -99))), base.rows[1]))
        assertTrue(problems(codeWithoutText).any { "not a character" in it })
    }
}
