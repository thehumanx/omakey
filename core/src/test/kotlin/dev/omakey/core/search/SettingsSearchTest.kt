package dev.omakey.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    private val docs = listOf(
        SearchDocument("A", "Auto-capitalize", emptyList(), listOf("Punctuation & capitals", "Typing")),
        SearchDocument("B", "Haptic feedback", listOf("vibration", "vibrate"), listOf("Sound & haptics", "Sound & haptics")),
        SearchDocument("C", "Swipe right for space", emptyList(), listOf("Gestures", "Gestures")),
        SearchDocument("D", "Swipe the spacebar to change language", emptyList(), listOf("Gestures", "Gestures")),
        SearchDocument("E", "Theme", listOf("dark", "colour"), listOf("Theme", "Appearance")),
    )
    private val search = SettingsSearch(docs)
    private fun ids(q: String) = search.search(q).map { it.id }

    @Test
    fun `blank and punctuation-only queries return nothing`() {
        assertEquals(emptyList<String>(), ids(""))
        assertEquals(emptyList<String>(), ids("   "))
        assertEquals(emptyList<String>(), ids(" - "))
    }

    @Test
    fun `case and diacritics fold`() {
        assertEquals(listOf("B"), ids("HAPTÍC"))
    }

    @Test
    fun `hyphenated titles match joined and by part`() {
        assertEquals(listOf("A"), ids("autocap"))
        assertEquals(listOf("A"), ids("capit"))
    }

    @Test
    fun `prefix not infix`() {
        assertEquals(emptyList<String>(), ids("ipe"))
    }

    @Test
    fun `all tokens must match`() {
        assertEquals(listOf("D"), ids("swipe lang"))
        assertEquals(emptyList<String>(), ids("swipe zzz"))
    }

    @Test
    fun `ranking tiers`() {
        val swipe = search.search("swipe")
        assertEquals(listOf("C", "D"), swipe.map { it.id })
        assertEquals(listOf(0, 0), swipe.map { it.tier })
        val space = search.search("space")
        assertEquals(listOf("C", "D"), space.map { it.id })
        assertEquals(listOf(1, 1), space.map { it.tier })
        val gestures = search.search("gestures")
        assertEquals(listOf("C", "D"), gestures.map { it.id })
        assertEquals(listOf(3, 3), gestures.map { it.tier })
        // A title hit outranks a context-only hit regardless of index order.
        val theme = search.search("theme")
        assertEquals("E", theme.first().id)
        assertEquals(0, theme.first().tier)
    }

    @Test
    fun `title hit outranks keyword hit outranks context hit`() {
        val s = SettingsSearch(
            listOf(
                SearchDocument(1, "Gestures help", emptyList(), listOf("Other", "Other")),
                SearchDocument(2, "Swipe", listOf("gesture"), listOf("Other", "Other")),
                SearchDocument(3, "Long press", emptyList(), listOf("Gestures", "Gestures")),
            ),
        )
        assertEquals(listOf(1, 2, 3), s.search("gest").map { it.id })
        assertEquals(listOf(0, 2, 3), s.search("gest").map { it.tier })
    }

    @Test
    fun `keyword hit reports keyword`() {
        val hit = search.search("vibr").single()
        assertEquals("B", hit.id)
        assertEquals(2, hit.tier)
        assertEquals("vibration", hit.matchedKeyword)
        assertEquals(emptyList<IntRange>(), hit.titleHighlights)
    }

    @Test
    fun `title hit reports no keyword`() {
        assertEquals(null, search.search("haptic").single().matchedKeyword)
    }

    @Test
    fun `highlight ranges point into the original title`() {
        assertEquals(listOf(0..1, 6..7), search.search("sw ri").single().titleHighlights)
    }

    @Test
    fun `highlight on hyphenated title stays inside it`() {
        val hit = search.search("auto").single()
        assertEquals(listOf(0..3), hit.titleHighlights)
    }

    @Test
    fun `non-ascii query does not crash`() {
        assertEquals(emptyList<String>(), ids("नमस्ते"))
        ids("é")
        search.search("ü ß ﬁ").forEach { hit ->
            val title = docs.first { it.id == hit.id }.title
            hit.titleHighlights.forEach { assertTrue(it.last < title.length) }
        }
    }

    @Test
    fun `joined-form match is bolded across the parts`() {
        assertEquals(listOf(0..3, 5..7), search.search("autocap").single().titleHighlights)
    }

    @Test
    fun `devanagari words keep their vowel signs`() {
        val title = "नमस्ते दुनिया"
        assertEquals(listOf("नमस्ते", "दुनिया"), SearchText.words(title))
        assertEquals("नमस्ते", SearchText.normalize("नमस्ते"))
        val s = SettingsSearch(listOf(SearchDocument(1, title, emptyList(), emptyList())))
        assertEquals(listOf(1), s.search("नमस्").map { it.id })
        assertEquals(listOf(1), s.search("दुनि").map { it.id })
        s.search("दुनि").single().titleHighlights.forEach { assertTrue(it.last < title.length) }
    }

    @Test
    fun `normalize folds case and marks`() {
        assertEquals("cafe", SearchText.normalize("Café"))
        assertEquals(listOf("auto", "capitalize", "autocapitalize"), SearchText.words("Auto-capitalize"))
    }
}
