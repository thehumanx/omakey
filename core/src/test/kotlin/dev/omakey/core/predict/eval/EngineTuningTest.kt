package dev.omakey.core.predict.eval

import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.PersonalLanguageModel
import dev.omakey.core.predict.spatial.ChannelModel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Parameter sweep for the correction scorer. **Opt-in** — run with `-Domakey.tune=true`; a normal
 * `:core:test` skips it, since a full sweep takes minutes.
 *
 * Exists because these constants cannot be reasoned to. σ, the substitution cap, the per-edit costs
 * and the language-model weight all trade the same two quantities against each other, and the
 * trade is not monotonic: the first hand-picked values *lost* accuracy against the simpler ranking
 * they replaced, and only a sweep showed why.
 *
 * ## The objective
 *
 * [Objective.netBenefit] is `fixed − MISCORRECTION_PENALTY × miscorrected`. Correction accuracy on
 * its own is the wrong thing to maximise: an engine that corrects everything scores well on it
 * while being unusable. A miscorrection is also strictly worse for the user than a miss — a miss
 * leaves a typo they were going to fix anyway, a miscorrection silently substitutes a *different
 * word*, which they may not notice and must then undo. Hence the penalty weight above 1.
 *
 * ## What this corpus can and cannot tell you
 *
 * The misspellings here are **cognitive** — "definately", "recieve" — not touchscreen slips. That
 * matters for the spatial parameters specifically: `a`→`i` in "definately" is a long way across the
 * keyboard, so a geometry-aware channel *penalises* the right answer here, while on a real device
 * the same substitution would rarely occur without the finger being near `i`. So a sweep against
 * this corpus will push σ up and the cap down — toward geometry mattering less — further than
 * real touch data would justify. Treat the spatial numbers it produces as an upper bound on σ,
 * and re-tune once real tap coordinates are available.
 */
class EngineTuningTest {

    private data class Objective(
        val label: String,
        val fixed: Double,
        val miscorrected: Double,
        val falseCorrections: Double,
        val strip: Double,
    ) {
        val netBenefit: Double get() = fixed - MISCORRECTION_PENALTY * miscorrected

        override fun toString(): String = String.format(
            "%-46s net=%+.4f  fixed=%.4f  wrong=%.4f  false=%.4f  strip=%.4f",
            label, netBenefit, fixed, miscorrected, falseCorrections, strip,
        )
    }

    @Test
    fun `sweep correction scorer parameters`() = runBlocking {
        assumeTrue("set -Domakey.tune=true to run the sweep", System.getProperty("omakey.tune") == "true")

        val evaluator = EngineEvaluator(
            spellPairs = EvalCorpus.spellErrors(limit = 3_000),
            sentences = EvalCorpus.sentences(limit = 150),
        )
        val model = TestLanguageModel.load()
        val results = mutableListOf<Objective>()

        suspend fun measure(label: String, index: AutocorrectIndex) {
            val report = evaluator.evaluate(ScorerTarget(label, index))
            results += Objective(
                label, report.correctionAccuracy, report.miscorrectionRate,
                report.falseCorrectionRate, report.suggestionRecall,
            )
            println(results.last())
        }

        println("=== language-model weight (channel defaults) ===")
        for (weight in listOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f)) {
            measure("lm weight $weight", AutocorrectIndex(ChannelModel(languageModelWeight = weight)).also {
                it.load(model, PersonalLanguageModel())
            })
        }

        println("=== sigma (spread of the key-distance Gaussian) ===")
        for (sigma in listOf(0.7f, 0.85f, 1.2f, 1.8f, 3.0f)) {
            measure("sigma $sigma", AutocorrectIndex(ChannelModel(sigma = sigma)).also {
                it.load(model, PersonalLanguageModel())
            })
        }

        println("=== substitution cap ===")
        for (cap in listOf(1.5f, 2.5f, 3.5f, 4.5f, 6.0f)) {
            measure("cap $cap", AutocorrectIndex(ChannelModel(substitutionCap = cap)).also {
                it.load(model, PersonalLanguageModel())
            })
        }

        println("=== insertion/deletion cost ===")
        for (cost in listOf(1.5f, 2.0f, 3.0f, 4.0f)) {
            measure("indel $cost", AutocorrectIndex(ChannelModel(insertionCost = cost, deletionCost = cost)).also {
                it.load(model, PersonalLanguageModel())
            })
        }

        println("=== max edits (structural bound on the candidate set) ===")
        for (edits in listOf(2, 3)) {
            measure("max edits $edits", AutocorrectIndex(maxEdits = edits, correctionRank = 25_000).also {
                it.load(model, PersonalLanguageModel())
            })
        }

        println("=== correction floor rank ===")
        for (rank in listOf(6_000, 10_000, 15_000, 25_000, 40_000)) {
            measure("floor rank $rank", AutocorrectIndex(correctionRank = rank).also {
                it.load(model, PersonalLanguageModel())
            })
        }

        println()
        println("=== best by net benefit ===")
        results.sortedByDescending { it.netBenefit }.take(10).forEach { println(it) }
    }

    /** Wraps a specific [AutocorrectIndex] configuration; prediction is untouched by these
     * parameters, so it is shared rather than rebuilt per configuration. */
    private class ScorerTarget(override val name: String, private val index: AutocorrectIndex) : EvaluationTarget {
        override fun correct(typed: String): String? = index.correct(typed)
        override fun alternatives(typed: String, limit: Int): List<String> = index.alternatives(typed, limit)
        override suspend fun suggestNext(
            beforePreviousWord: String?,
            previousWord: String?,
            prefix: String,
            limit: Int,
        ): List<String> = emptyList()
        override fun isKnown(word: String): Boolean = index.isKnown(word)
    }

    private companion object {
        /** How many misses one wrong substitution is worth. Above 1 because a miscorrection
         * replaces a word the user has to notice and undo, while a miss leaves a typo they were
         * already going to fix. */
        const val MISCORRECTION_PENALTY = 1.5
    }
}
