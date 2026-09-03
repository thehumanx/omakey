package dev.omakey.core.predict.eval

import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.NgramPredictionEngine
import dev.omakey.core.predict.PersonalLanguageModel
import dev.omakey.core.predict.PredictionEngine

/**
 * The surface [EngineEvaluator] measures. Deliberately narrower than any one production class:
 * correction and prediction currently live in two objects ([AutocorrectIndex] and
 * [PredictionEngine]) that the planned decoder collapses into one. Both shapes can implement this,
 * so numbers stay comparable across that rewrite instead of the benchmark having to be rewritten
 * alongside the thing it benchmarks.
 */
interface EvaluationTarget {
    val name: String

    /** The silent auto-apply decision: a replacement, or null to leave the word alone. */
    fun correct(typed: String): String?

    /** What the suggestion strip would offer, best first. */
    fun alternatives(typed: String, limit: Int): List<String>

    /** Next-word / completion candidates, best first. [prefix] is empty for a pure next-word ask. */
    suspend fun suggestNext(
        beforePreviousWord: String?,
        previousWord: String?,
        prefix: String,
        limit: Int,
    ): List<String>

    fun isKnown(word: String): Boolean
}

/** The shipping engine, wired the way `OmakeyInputMethodService.onCreate` wires it. */
class CurrentEngineTarget : EvaluationTarget {
    override val name = "n-gram model (mmap) + AutocorrectIndex"

    private val model = TestLanguageModel.load()
    private val personal = PersonalLanguageModel()
    private val index = AutocorrectIndex().apply { load(model, personal) }
    private val engine: PredictionEngine = NgramPredictionEngine(model, InMemoryWordDao(), personal)

    override fun correct(typed: String): String? = index.correct(typed)

    override fun alternatives(typed: String, limit: Int): List<String> = index.alternatives(typed, limit)

    override suspend fun suggestNext(
        beforePreviousWord: String?,
        previousWord: String?,
        prefix: String,
        limit: Int,
    ): List<String> = engine.suggestNext(beforePreviousWord, previousWord, prefix, limit)

    override fun isKnown(word: String): Boolean = index.isKnown(word)
}
