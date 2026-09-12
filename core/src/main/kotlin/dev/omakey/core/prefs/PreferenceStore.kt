package dev.omakey.core.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One `SharedPreferences` file, exposed as a [StateFlow] that stays correct across every instance
 * reading it.
 *
 * Thirteen `*Preferences` classes each wrote this out by hand, and had drifted into four different
 * shapes: some funnelled every setter through a shared `update {}` writing all keys, some wrote one
 * key per setter with no helper, `FontPreferences` skipped the settings data class entirely and
 * duplicated its default-fallback expression in two places, and `EmojiRecentsPreferences`
 * registered no change listener at all — so emoji recents, alone among every preference in the app,
 * did not sync between the Settings instance and the IME's. Four shapes for one job means every new
 * preference picks one at random, and the odd one out was a real (if small) bug nobody had noticed.
 *
 * ### Why the listener is load-bearing rather than decorative
 *
 * The Settings Activity and the IME service each construct their own instance of every preference
 * class, in the same process but with no reference to one another. Without the listener, changing a
 * setting in Settings would update only Settings' copy, and the running keyboard would keep the old
 * value until its process restarted.
 *
 * ### Why setters update the flow eagerly *and* let the listener fire
 *
 * `apply()` notifies listeners synchronously when called on the main thread, but posts to the main
 * looper otherwise — so relying on the listener alone would leave [settings] briefly stale for any
 * write that didn't originate on the main thread. Writing eagerly closes that window. The listener
 * then reloads and assigns the same value, which `StateFlow` conflates, so there is no second
 * emission and no extra recomposition — only a second read of an in-memory map.
 *
 * @param loader must be a pure read of [prefs]; it runs on construction and again on every change,
 *   including changes made by another instance.
 */
class PreferenceStore<T>(
    context: Context,
    prefsName: String,
    private val loader: (SharedPreferences) -> T,
) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loader(prefs))
    val settings: StateFlow<T> = _settings

    /** Held in a field because `SharedPreferences` keeps only a weak reference to registered
     * listeners: a lambda passed straight to `register…` and not otherwise retained can be
     * collected at any time, silently ending the cross-instance sync. */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = loader(prefs)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val value: T get() = _settings.value

    /** Writes [block]'s keys and refreshes [settings]. Callers describe only the keys they are
     * changing; re-deriving the whole value afterwards is what keeps the flow and the file from
     * disagreeing about anything else. */
    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = loader(prefs)
    }

    /**
     * Stops listening. Optional, and deliberately so — the weak reference above means failing to
     * call this leaks nothing. It exists so an owner with a definite end of life (the IME service)
     * can be explicit rather than relying on that, and so "never unregistered anywhere" stops being
     * true by omission rather than by decision.
     */
    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
