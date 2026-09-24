package dev.omakey.core.locale

import dev.omakey.core.layout.LayoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every language that can be typed right now: the bundled ones, plus any installed from a language
 * pack. Registering a language also registers its layouts with [layouts], so a language's shift
 * layer is resolvable the moment the language is.
 */
class LocaleRegistry(
    val layouts: LayoutRepository = LayoutRepository(),
    bundled: List<KeyboardLocale> = KeyboardLocale.bundled,
) {
    private val _available = MutableStateFlow(bundled)
    val available: StateFlow<List<KeyboardLocale>> = _available

    operator fun get(id: String): KeyboardLocale? = _available.value.firstOrNull { it.id == id }

    /** Adds [locale], replacing an earlier version with the same id — a pack update. Throws
     * [IllegalArgumentException] if its layouts are invalid, in which case nothing changes. */
    @Synchronized
    fun register(locale: KeyboardLocale) {
        layouts.registerAll(listOf(locale.letterLayout) + locale.extraLayouts)
        _available.value = _available.value.filter { it.id != locale.id } + locale
    }

    /** Removes an installed language. Bundled ones stay: there is nothing to uninstall. */
    @Synchronized
    fun unregister(id: String) {
        if (KeyboardLocale.bundled.any { it.id == id }) return
        _available.value = _available.value.filter { it.id != id }
    }

    /** [ids] resolved to languages that exist, in order; unknown ids (a pack that was removed) are
     * dropped, and the default language stands in if nothing is left. */
    fun resolve(ids: List<String>): List<KeyboardLocale> =
        ids.mapNotNull(::get).ifEmpty { listOf(KeyboardLocale.Default) }
}
