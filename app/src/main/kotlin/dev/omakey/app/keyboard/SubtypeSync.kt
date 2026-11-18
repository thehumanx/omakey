package dev.omakey.app.keyboard

import android.content.ComponentName
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.locale.SubtypeSpec
import dev.omakey.core.locale.localeIdForLanguageTag
import dev.omakey.core.locale.subtypeSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the system's idea of omakey's language in step with omakey's own (see [SubtypeSpec] for
 * why: the system spellchecker follows the keyboard's current subtype).
 *
 *  - Each enabled language is registered as an additional subtype whenever the list changes. English
 *    is the static subtype in `res/xml/method.xml` and isn't registered again.
 *  - When the active language changes, the system's current subtype is switched to match.
 *  - When the user picks a subtype from the system's keyboard switcher, omakey switches language.
 *
 * Not device-tested at the time of writing. Some Android builds only let a keyboard switch to a
 * subtype the user has enabled in system settings; if switching doesn't take on a phone, the next
 * step is `setOverridesImplicitlyEnabledSubtype(true)` on the registered subtypes.
 */
class SubtypeSync(
    private val service: InputMethodService,
    private val localeManager: LocaleManager,
    private val scope: CoroutineScope,
) {
    private val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val imeId = ComponentName(service, service.javaClass).flattenToShortString()

    fun start() {
        scope.launch {
            localeManager.enabled.map { languages -> languages.map { it.subtypeSpec() } }.distinctUntilChanged().collect(::register)
        }
        scope.launch {
            localeManager.active.map { it.id }.distinctUntilChanged().collect { id -> switchSystemTo(localeManager.active.value) }
        }
    }

    /** The keyboard is showing for a field. The switch in [start] runs during onCreate, before the
     * system has bound this keyboard, where Android ignores it without an error; repeating it here
     * is what makes the first one stick. Cheap: [switchSystemTo] returns early when they agree. */
    fun onInputViewStarted() = switchSystemTo(localeManager.active.value)

    /** The system's current subtype changed — from its keyboard switcher, or as the echo of our own
     * [switchSystemTo]. Either way: follow it if it names a different enabled language. */
    fun onSystemSubtypeChanged(subtype: InputMethodSubtype?) {
        val tag = subtype?.let(::languageTagOf) ?: return
        val id = localeIdForLanguageTag(tag, localeManager.enabled.value) ?: return
        if (id != localeManager.active.value.id) localeManager.switchTo(id, remember = true)
    }

    private fun register(specs: List<SubtypeSpec>) {
        val additional = specs.filter { it.localeId != KeyboardLocale.EnUs.id }.map(::build).toTypedArray()
        try {
            @Suppress("DEPRECATION") // still the way for a keyboard to declare subtypes at runtime
            imm.setAdditionalInputMethodSubtypes(imeId, additional)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't register language subtypes", e)
        }
        switchSystemTo(localeManager.active.value)
    }

    private fun switchSystemTo(locale: KeyboardLocale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val spec = locale.subtypeSpec()
        val current = imm.currentInputMethodSubtype
        if (current != null && current.hashCode() == build(spec).hashCode()) return
        if (current != null && localeIdForLanguageTag(languageTagOf(current), listOf(locale)) == locale.id) return
        try {
            service.switchInputMethod(imeId, build(spec))
        } catch (e: RuntimeException) {
            // The subtype isn't known to the system yet (registration still in flight) or this build
            // refuses it; the next language change or registration tries again.
            Log.w(TAG, "Couldn't switch the system subtype to ${locale.id}", e)
        }
    }

    private fun build(spec: SubtypeSpec): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeId(spec.subtypeId)
            .setLanguageTag(spec.languageTag)
            .setSubtypeLocale(spec.localeId)
            .setSubtypeMode(MODE_KEYBOARD)
            .setIsAsciiCapable(spec.asciiCapable)
            .setSubtypeNameOverride(spec.label)
            .setOverridesImplicitlyEnabledSubtype(false)
            .build()

    private fun languageTagOf(subtype: InputMethodSubtype): String =
        subtype.languageTag.ifBlank {
            @Suppress("DEPRECATION")
            subtype.locale
        }

    private companion object {
        const val TAG = "SubtypeSync"
        const val MODE_KEYBOARD = "keyboard"
    }
}
