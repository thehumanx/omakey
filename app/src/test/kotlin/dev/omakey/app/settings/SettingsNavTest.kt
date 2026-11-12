package dev.omakey.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavTest {

    @Test
    fun `page opened from search goes back to search with the query kept`() {
        val searching = SettingsNav().openSearch().withQuery("swi")
        val onPage = searching.openResult(SettingKey.SWIPE_RIGHT_FOR_SPACE)
        assertEquals(SettingsPage.GESTURES, onPage.page)
        assertEquals(SettingsNav(page = null, searchOpen = true, query = "swi"), onPage.back())
    }

    @Test
    fun `back from search closes it and clears the query`() {
        assertEquals(SettingsNav(), SettingsNav(searchOpen = true, query = "swi").back())
    }

    @Test
    fun `back from home leaves settings`() {
        assertNull(SettingsNav().back())
    }

    @Test
    fun `page opened from home goes back home`() {
        assertEquals(SettingsNav(), SettingsNav().openPage(SettingsPage.TYPING).back())
    }

    @Test
    fun `opening search starts empty`() {
        assertEquals(SettingsNav(searchOpen = true, query = ""), SettingsNav(query = "old").openSearch())
    }
}
