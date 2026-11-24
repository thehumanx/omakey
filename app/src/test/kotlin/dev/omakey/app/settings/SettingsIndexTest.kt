package dev.omakey.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {

    private val search = SettingsIndex.searchFor(sdkInt = 34)

    @Test
    fun `titles are unique and non-blank`() {
        val titles = SettingKey.entries.map { it.title }
        assertTrue(titles.all { it.isNotBlank() })
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `keywords are lowercase`() {
        SettingKey.entries.forEach { key ->
            key.keywords.forEach { assertEquals("$key: $it", it.lowercase(), it) }
        }
    }

    @Test
    fun `every section has an entry and every page a section`() {
        SettingsSectionKey.entries.forEach { section ->
            assertTrue("$section has no entry", SettingKey.entries.any { it.section == section })
        }
        SettingsPage.entries.forEach { page ->
            assertTrue("$page has no section", SettingsIndex.sectionsOf(page).isNotEmpty())
        }
    }

    @Test
    fun `section-level entries carry their section's title`() {
        SettingKey.entries.filter { it.sectionLevel }.forEach { assertEquals(it.section.title, it.title) }
    }

    @Test
    fun `spacebar language entry is under Gestures`() {
        val key = SettingKey.entries.single { it.title == "Swipe the spacebar to change language" }
        assertEquals(SettingsPage.GESTURES, key.section.page)
    }

    @Test
    fun `real queries land where expected`() {
        assertEquals(SettingKey.HAPTIC_FEEDBACK, search.search("vibrate").first().id)
        assertEquals(SettingKey.THEME, search.search("dark").first().id)
        val swipeSpace = search.search("swipe space").map { it.id }
        assertTrue(SettingKey.SWIPE_RIGHT_FOR_SPACE in swipeSpace)
        assertTrue(SettingKey.SWIPE_SPACEBAR_LANGUAGE in swipeSpace)
        assertEquals(SettingKey.AUTO_CAPITALIZE, search.search("autocap").first().id)
        assertTrue(SettingKey.NUMBER_PAD in search.search("phone").map { it.id }) // title hit "What leaves your phone" ranks first
    }

    @Test
    fun `entries needing a newer Android are left out on older ones`() {
        assertTrue(SettingKey.SYSTEM_ACCENT in search.search("accent").map { it.id })
        assertFalse(SettingKey.SYSTEM_ACCENT in SettingsIndex.searchFor(sdkInt = 30).search("accent").map { it.id })
    }

    @Test
    fun `accent still finds the theme picker on older Android`() {
        assertEquals(SettingKey.THEME, SettingsIndex.searchFor(sdkInt = 30).search("accent").firstOrNull()?.id)
    }

    @Test
    fun `home lists four groups in the designed order`() {
        assertEquals(listOf("Look", "Typing", "Above the keys", "More"), SettingsGroup.entries.map { it.title })
        assertEquals(
            listOf(SettingsPage.THEME, SettingsPage.KEYS, SettingsPage.FONT, SettingsPage.SIZE_POSITION),
            SettingsIndex.pagesIn(SettingsGroup.LOOK),
        )
        assertEquals(
            listOf(SettingsPage.CORRECTIONS, SettingsPage.GESTURES, SettingsPage.LANGUAGES),
            SettingsIndex.pagesIn(SettingsGroup.TYPING),
        )
        assertEquals(
            listOf(SettingsPage.BAR_PAGES, SettingsPage.QUICK_ACCESS, SettingsPage.CLIPBOARD),
            SettingsIndex.pagesIn(SettingsGroup.ABOVE_THE_KEYS),
        )
        assertEquals(
            listOf(SettingsPage.SOUND, SettingsPage.PRIVACY, SettingsPage.ACCESSIBILITY, SettingsPage.ABOUT),
            SettingsIndex.pagesIn(SettingsGroup.MORE),
        )
    }

    @Test
    fun `related settings now sit together`() {
        assertEquals(SettingsPage.CLIPBOARD, SettingKey.CLIPBOARD_HISTORY.section.page)
        assertEquals(SettingsPage.CLIPBOARD, SettingKey.SAVE_CLIPBOARD_HISTORY.section.page)
        assertEquals(SettingsPage.KEYS, SettingKey.TAP_PREVIEW.section.page)
        assertEquals(SettingsPage.KEYS, SettingKey.LAYOUT_STYLE.section.page)
        assertEquals(SettingsPage.KEYS, SettingKey.NUMBER_PAD.section.page)
        assertEquals(SettingsPage.SIZE_POSITION, SettingKey.SPLIT_DUPLICATE_KEYS.section.page)
        assertEquals(SettingsPage.BAR_PAGES, SettingKey.AUTOFILL.section.page)
        assertEquals(SettingsPage.CORRECTIONS, SettingKey.AUTO_CAPITALIZE.section.page)
        assertEquals(SettingsPage.KEYS, SettingKey.EMOJI_SKIN_TONE.section.page)
        assertEquals("Extension bar pages", SettingsPage.BAR_PAGES.title)
        assertEquals(SettingKey.QUICK_ACCESS_TILES, search.search("quick settings").first().id)
    }
}
