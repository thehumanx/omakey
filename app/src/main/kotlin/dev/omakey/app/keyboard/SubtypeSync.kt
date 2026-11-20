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
        safely("Couldn't register language subtypes") {
            val additional = specs.filter { it.localeId != KeyboardLocale.EnUs.id }.map(::build).toTypedArray()
            @Suppress("DEPRECATION") // still the way for a keyboard to declare subtypes at runtime
            imm.setAdditionalInputMethodSubtypes(imeId, additional)
        }
        switchSystemTo(localeManager.active.value)
    }

    /** Fails quietly when the subtype isn't known to the system yet (registration still in
     * flight) or this build refuses it; the next language change or registration tries again. */
    private fun switchSystemTo(locale: KeyboardLocale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        safely("Couldn't switch the system subtype to ${locale.id}") {
            val spec = locale.subtypeSpec()
            val current = imm.currentInputMethodSubtype
            if (current != null && current.hashCode() == build(spec).hashCode()) return@safely
            if (current != null && localeIdForLanguageTag(languageTagOf(current), listOf(locale)) == locale.id) return@safely
            service.switchInputMethod(imeId, build(spec))
        }
    }

    /**
     * Telling the system the language is a nicety; the keyboard must work without it. Catches
     * `LinkageError` as well as exceptions: a framework method missing on an older Android version
     * (`NoSuchMethodError`) is exactly what crashed 5.7.0–5.8.1 at startup on Android 13 and older.
     */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, what, e)
        } catch (e: LinkageError) {
            Log.w(TAG, what, e)
        }
    }

    /**
     * The name override is Android 14+ only. 5.7.0–5.8.1 called it unconditionally, and on
     * Android 13 and older the missing method threw `NoSuchMethodError` the moment the keyboard
     * started, which the `RuntimeException` catches above don't catch: omakey crashed as soon as it
     * was chosen as the keyboard (Android 10 and a Galaxy S20 FE). Older versions name the subtype
     * from its locale instead. Lint's NewApi check is now part of every release (AGENTS.md).
     */
    private fun build(spec: SubtypeSpec): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeId(spec.subtypeId)
            .setLanguageTag(spec.languageTag)
            .setSubtypeLocale(spec.localeId)
            .setSubtypeMode(MODE_KEYBOARD)
            .setIsAsciiCapable(spec.asciiCapable)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setSubtypeNameOverride(spec.label) }
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
