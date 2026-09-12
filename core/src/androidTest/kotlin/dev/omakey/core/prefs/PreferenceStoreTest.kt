package dev.omakey.core.prefs

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented rather than a plain JVM test because the behaviour worth testing *is* the real
 * `SharedPreferences` — specifically that two separately-constructed instances over the same file
 * stay in sync, which is the property every `*Preferences` class in the app depends on and which a
 * stub implementation would simply assert into existence.
 */
@RunWith(AndroidJUnit4::class)
class PreferenceStoreTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun store() = PreferenceStore(context, PREFS_NAME) { it.getInt(KEY, 0) }

    @Before fun clean() = wipe()

    @After fun cleanUp() = wipe()

    private fun wipe() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * Writes the way the app does: on the main thread.
     *
     * This is not ceremony. `SharedPreferencesImpl` dispatches change notifications inline when
     * `apply()` is called on the main looper and *posts* them otherwise, so a write from the
     * instrumentation thread reaches other instances only after the main looper next runs — these
     * tests failed exactly that way before this wrapper existed. Every real caller (the Settings
     * Activity, the IME service) is on the main thread, and [PreferenceStore.edit] updating its own
     * flow eagerly is what covers the case where one isn't.
     */
    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Test
    fun loads_the_persisted_value_on_construction() {
        store().edit { putInt(KEY, 42) }

        assertEquals(42, store().value)
    }

    @Test
    fun defaults_when_nothing_is_persisted() {
        assertEquals(0, store().value)
    }

    @Test
    fun edit_updates_the_flow_immediately() {
        val store = store()

        store.edit { putInt(KEY, 7) }

        assertEquals(7, store.settings.value)
    }

    @Test
    fun a_write_through_one_instance_reaches_another() {
        // The whole reason the change listener exists: Settings and the IME service each construct
        // their own instance, in one process, with no reference to each other. Without this, a
        // setting changed in Settings would not reach a keyboard that was already open.
        val settingsSide = store()
        val keyboardSide = store()

        onMainThread { settingsSide.edit { putInt(KEY, 99) } }

        assertEquals(99, keyboardSide.value)
    }

    @Test
    fun a_closed_store_stops_receiving_updates() {
        val writer = store()
        val reader = store()

        reader.close()
        onMainThread { writer.edit { putInt(KEY, 5) } }

        assertEquals(0, reader.value)
    }

    @Test
    fun closing_one_store_leaves_the_others_listening() {
        // close() is per-instance; the IME closing its own copies on onDestroy must not silently
        // deafen the Settings Activity's.
        val writer = store()
        val closed = store()
        val stillOpen = store()

        closed.close()
        onMainThread { writer.edit { putInt(KEY, 3) } }

        assertEquals(3, stillOpen.value)
    }

    private companion object {
        const val PREFS_NAME = "omakey_preference_store_test"
        const val KEY = "value"
    }
}
