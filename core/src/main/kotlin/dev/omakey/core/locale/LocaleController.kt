package dev.omakey.core.locale

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The keyboard's view of language switching: which language is active, which the user switches
 * between, and how to switch. An interface so `KeyboardViewModel` depends on the behaviour rather
 * than on the Android-backed implementation that loads models and reads preferences.
 */
interface LocaleController {
    val active: StateFlow<KeyboardLocale>

    /** Languages the user has enabled, in switching order. Never empty. */
    val enabled: StateFlow<List<KeyboardLocale>>

    /**
     * Makes [id] the active language, if it is enabled. [remember] = false switches for the current
     * text field only — used when a field asks for a language (`EditorInfo.hintLocales`), which
     * should not change what the keyboard opens in next time.
     */
    fun switchTo(id: String, remember: Boolean = true)

    /**
     * A new text field started. If it names languages it expects (`EditorInfo.hintLocales`, as ISO
     * 639 codes, most preferred first) and one of them is enabled, switch to it for this field only;
     * otherwise return to the language the user last chose, so one field's hint doesn't stick.
     */
    fun onFieldStarted(hintLanguages: List<String>)

    /** Makes [layoutId] [localeId]'s letter layout, remembered for next time. No-op by default. */
    fun chooseLayout(localeId: String, layoutId: String) = Unit

    /**
     * The globe key: the next stop in a cycle through every enabled language *and* each of its
     * layouts — English, Spanish, French AZERTY, French QWERTY, Nepali Romanized, Nepali Devanagari,
     * back to English. A language with several layouts is entered at its first, so one tap always
     * leads somewhere predictable, and the cycle reaches every layout without opening a menu.
     */
    fun next() {
        val languages = enabled.value
        val current = active.value
        val layouts = current.letterLayoutChoices
        val layoutIndex = layouts.indexOfFirst { it.id == current.letterLayout.id }
        if (layouts.size > 1 && layoutIndex in 0 until layouts.lastIndex) {
            chooseLayout(current.id, layouts[layoutIndex + 1].id)
            return
        }
        if (languages.size < 2) {
            if (layouts.size > 1) chooseLayout(current.id, layouts.first().id)
            return
        }
        val index = languages.indexOfFirst { it.id == current.id }
        val nextLanguage = languages[(index + 1).mod(languages.size)]
        nextLanguage.letterLayoutChoices.firstOrNull()?.let { chooseLayout(nextLanguage.id, it.id) }
        switchTo(nextLanguage.id)
    }
}

/** One language, never switching — the default where no real controller is supplied. */
class FixedLocaleController(locale: KeyboardLocale = KeyboardLocale.Default) : LocaleController {
    override val active: StateFlow<KeyboardLocale> = MutableStateFlow(locale)
    override val enabled: StateFlow<List<KeyboardLocale>> = MutableStateFlow(listOf(locale))
    override fun switchTo(id: String, remember: Boolean) = Unit
    override fun onFieldStarted(hintLanguages: List<String>) = Unit
}
