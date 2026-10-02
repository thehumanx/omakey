package dev.omakey.app.keyboard

import android.content.Context
import android.util.Log
import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity
import dev.omakey.core.layout.Layouts
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.locale.LocaleController
import dev.omakey.core.locale.LocalePreferences
import dev.omakey.core.locale.LocaleRegistry
import dev.omakey.core.locale.ModelSource
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.DeferredPredictionEngine
import dev.omakey.core.predict.NgramPredictionEngine
import dev.omakey.core.predict.PersonalLanguageModel
import dev.omakey.core.predict.lm.LanguageModel
import dev.omakey.core.predict.spatial.KeyboardGeometry
import dev.omakey.core.translit.TransliterationIndex
import dev.omakey.core.translit.TransliterationScheme
import dev.omakey.core.translit.Transliterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the active language and keeps the prediction stack loaded for it (AGENTS.md §66 Phase 5).
 *
 * Switching loads three things, all per language: the memory-mapped model, the user's personal
 * vocabulary for that language (`words` rows with its `locale`), and autocorrect's key geometry,
 * derived from the language's own letter layout. The previous language's prediction engine is
 * withdrawn first, so for the moment a switch takes the strip shows "loading" rather than the old
 * language's suggestions — and a switch arriving mid-load cancels the stale one ([collectLatest]).
 *
 * A fresh [PersonalLanguageModel] per load rather than reloading one shared instance: it is a plain
 * `HashMap`, and the typing thread may be reading the old one while this builds the new one.
 */
class LocaleManager(
    private val context: Context,
    private val registry: LocaleRegistry,
    private val preferences: LocalePreferences,
    private val wordDao: WordDao,
    private val autocorrectIndex: AutocorrectIndex,
    private val predictionEngine: DeferredPredictionEngine,
    private val scope: CoroutineScope,
) : LocaleController {

    override val enabled: StateFlow<List<KeyboardLocale>> =
        combine(registry.available, preferences.settings) { _, settings -> resolve(settings) }
            .stateIn(scope, SharingStarted.Eagerly, resolve(preferences.settings.value))

    /** Enabled languages, each with the letter layout the user picked for it. */
    private fun resolve(settings: dev.omakey.core.locale.LocaleSettings): List<KeyboardLocale> =
        registry.resolve(settings.enabledIds).map { it.withLetterLayout(settings.layoutChoices[it.id]) }

    private val _transliterator = MutableStateFlow<Transliterator?>(null)

    /** The active language's transliterator, once loaded — for languages whose layouts type in
     * Latin letters (Nepali). */
    val transliterator: StateFlow<Transliterator?> = _transliterator

    private val _active = MutableStateFlow(initialLocale())
    override val active: StateFlow<KeyboardLocale> = _active

    init {
        scope.launch { _active.collectLatest(::load) }
        // Disabling the active language in Settings (or removing its pack) moves to the first one
        // still enabled, rather than leaving the keyboard in a language the user just turned off.
        scope.launch {
            enabled.collect { languages ->
                val current = _active.value
                val replacement = languages.firstOrNull { it.id == current.id } ?: languages.first()
                // Equality, not identity: the list is rebuilt on every preference change, and only a
                // real difference (an updated pack, another layout chosen) is worth a reload.
                if (replacement != current) _active.value = replacement
            }
        }
    }

    override fun switchTo(id: String, remember: Boolean) {
        // Layout from the preferences directly, not from `enabled`: that flow catches up a moment
        // later, and chooseLayout followed by switchTo (the globe key's cycle) must land on the
        // layout just chosen rather than load the old one and then reload.
        val locale = enabled.value.firstOrNull { it.id == id }
            ?.withLetterLayout(preferences.settings.value.layoutChoices[id]) ?: return
        if (remember) preferences.setActive(id)
        _active.value = locale
    }

    override fun chooseLayout(localeId: String, layoutId: String) {
        preferences.setLayoutChoice(localeId, layoutId)
        val current = _active.value
        if (current.id == localeId) _active.value = current.withLetterLayout(layoutId)
    }

    override fun onFieldStarted(hintLanguages: List<String>) {
        val languages = enabled.value
        val hinted = hintLanguages.firstNotNullOfOrNull { hint -> languages.firstOrNull { it.language == hint } }
        val target = hinted ?: languages.firstOrNull { it.id == preferences.settings.value.activeId } ?: languages.first()
        if (target.id != _active.value.id) _active.value = target
    }

    private fun initialLocale(): KeyboardLocale {
        val languages = enabled.value
        return languages.firstOrNull { it.id == preferences.settings.value.activeId } ?: languages.first()
    }

    private suspend fun load(locale: KeyboardLocale) {
        predictionEngine.delegate = null
        _transliterator.value = null
        try {
            val model = withContext(Dispatchers.IO) {
                when (val source = locale.languageModel) {
                    is ModelSource.Asset -> LanguageModel.load(context, source.name)
                    is ModelSource.File -> LanguageModel.load(File(source.path))
                }
            }
            val personal = PersonalLanguageModel()
            val rows = wordDao.allUserAdded(locale.id)
            personal.load(
                rows.map {
                    PersonalLanguageModel.Entry(
                        word = it.word,
                        count = it.frequency / WordEntity.COUNT_SCALE,
                        lastUsed = it.lastUsedTimestamp,
                        explicit = it.explicit,
                    )
                },
                model,
            )
            autocorrectIndex.load(model, personal, locale.profile, geometryFor(locale))
            predictionEngine.delegate = NgramPredictionEngine(model, wordDao, personal, locale.id, locale.profile)
            val source = locale.languageModel
            val transliterates = (listOf(locale.letterLayout) + locale.letterLayoutChoices).any { it.transliteration }
            val scheme = TransliterationScheme.forLanguage(locale.language)
            if (transliterates && scheme == null) Log.w(TAG, "${locale.id} has a transliteration layout but no scheme")
            if (transliterates && scheme != null && source is ModelSource.File) {
                // Built once per install, next to the model, then memory-mapped (see
                // TransliterationIndex for why it's built here and not shipped in the pack).
                val index = withContext(Dispatchers.IO) {
                    TransliterationIndex.openOrBuild(File(File(source.path).parentFile, "translit.idx"), model, scheme)
                }
                _transliterator.value = Transliterator(model, index, scheme)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Typing still works without a model; only suggestions and correction are lost.
            Log.e(TAG, "Language model for ${locale.id} unavailable; typing works, suggestions won't", e)
        }
    }

    /** English keeps the exact geometry instance its tuning and golden tests use; every other
     * language derives one from its own letter layout. */
    private fun geometryFor(locale: KeyboardLocale): KeyboardGeometry =
        if (locale.letterLayout == Layouts.QwertyEnUS) {
            KeyboardGeometry.QWERTY
        } else {
            KeyboardGeometry.from(locale.letterLayout, locale.profile::isWordChar)
        }

    private companion object {
        const val TAG = "LocaleManager"
    }
}
