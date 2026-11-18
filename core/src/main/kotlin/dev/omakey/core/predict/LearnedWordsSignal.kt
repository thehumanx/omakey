package dev.omakey.core.predict

import android.content.Context
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Tells the running keyboard that Settings changed the learned words (edit, remove, delete all).
 *
 * The keyboard holds each language's learned words in memory ([PersonalLanguageModel]) and used
 * to hear about Settings' changes only on its next restart: a removed word stayed protected from
 * autocorrect and an edited one kept its old spelling meanwhile (closed-beta report: "learned
 * words can't be edited"). A counter in SharedPreferences, through the same change-listener
 * pattern every other preference here uses, rather than a database observer: the keyboard's own
 * writes to the `words` table happen on every word typed and must not trigger a reload.
 */
class LearnedWordsSignal(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME) { it.getInt(KEY_GENERATION, 0) }

    /** Changes whenever Settings edits the learned words; the value itself means nothing. */
    val changes: StateFlow<Int> = store.settings

    fun bump() = store.edit { putInt(KEY_GENERATION, store.value + 1) }

    fun close() = store.close()

    private companion object {
        const val PREFS_NAME = "omakey_learned_words_signal"
        const val KEY_GENERATION = "generation"
    }
}
