package dev.omakey.app.settings

/**
 * Where the user is in Settings: an open [page] (null = the home list) on top of an optional
 * search. Back unwinds one layer at a time: page → search (query kept) → home → leave.
 */
internal data class SettingsNav(
    val page: SettingsPage? = null,
    val searchOpen: Boolean = false,
    val query: String = "",
) {
    /** One step back, or null when Back should leave Settings. */
    fun back(): SettingsNav? = when {
        page != null -> copy(page = null)
        searchOpen -> SettingsNav()
        else -> null
    }

    fun openPage(page: SettingsPage) = copy(page = page)

    fun openSearch() = SettingsNav(searchOpen = true)

    fun withQuery(query: String) = copy(query = query)

    fun openResult(key: SettingKey) = copy(page = key.section.page)
}
