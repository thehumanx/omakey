package dev.omakey.core.predict.eval

/**
 * Scores an [EvaluationTarget] on the fixture corpora and prints a comparable report.
 *
 * Exists because every knob in a correction engine — edit-distance beam, frequency floors, and
 * later the spatial model's σ and the auto-apply margin — is an empirical trade-off between
 * fixing more typos and corrupting more correctly-typed words. Tuning one without measuring the
 * other is how autocorrect earns its reputation. So the two are always reported together:
 * [Report.correctionAccuracy] and [Report.falseCorrectionRate] move in opposite directions and
 * only mean something as a pair.
 */
class EngineEvaluator(
    private val spellPairs: List<EvalCorpus.SpellPair>,
    private val sentences: List<List<String>>,
) {

    data class Report(
        val target: String,
        /** Share of misspellings whose intended word is in the dictionary at all — an upper bound
         * on every correction metric below, and reported separately so a coverage problem is never
         * mistaken for a ranking problem. */
        val dictionaryCoverage: Double,
        /** Of the correctable pairs, how often the silent auto-apply produced exactly the intended
         * word. */
        val correctionAccuracy: Double,
        /** How often auto-apply changed the word into something that was *not* what was intended —
         * strictly worse than leaving it alone, since the user now has to undo it. */
        val miscorrectionRate: Double,
        /** How often auto-apply declined to act on a genuine misspelling. */
        val missRate: Double,
        /** Whether the intended word appears anywhere in the strip's candidate list — the
         * recoverable case, since the user can swipe to it. */
        val suggestionRecall: Double,
        /** Correctly-spelled words that auto-apply changed anyway. The metric users feel most
         * sharply, and the one a naive accuracy chase always makes worse. */
        val falseCorrectionRate: Double,
        /** Next word present in the top 3 with no prefix typed. */
        val nextWordRecall: Double,
        /** Next word present in the top 3 after its first two characters are typed. */
        val completionRecall: Double,
        val medianCorrectionMicros: Long,
    ) {
        fun format(): String = buildString {
            appendLine("=".repeat(72))
            appendLine("engine evaluation: $target")
            appendLine("=".repeat(72))
            appendLine(row("dictionary coverage of intended words", dictionaryCoverage))
            appendLine("-".repeat(72))
            appendLine("CORRECTION (of genuine misspellings, intended word in dictionary)")
            appendLine(row("  fixed correctly", correctionAccuracy))
            appendLine(row("  changed to the wrong word", miscorrectionRate))
            appendLine(row("  left uncorrected", missRate))
            appendLine(row("  intended word offered in strip", suggestionRecall))
            appendLine("-".repeat(72))
            appendLine("DAMAGE (correctly-typed words)")
            appendLine(row("  falsely corrected", falseCorrectionRate))
            appendLine("-".repeat(72))
            appendLine("PREDICTION")
            appendLine(row("  next word in top 3 (no prefix)", nextWordRecall))
            appendLine(row("  next word in top 3 (2-char prefix)", completionRecall))
            appendLine("-".repeat(72))
            appendLine(String.format("%-50s %,10d us", "median correct() latency", medianCorrectionMicros))
            appendLine("=".repeat(72))
        }

        private fun row(label: String, value: Double) = String.format("%-50s %9.2f %%", label, value * 100)
    }

    suspend fun evaluate(target: EvaluationTarget): Report {
        val correctable = spellPairs.filter { target.isKnown(it.correct) }
        val coverage = if (spellPairs.isEmpty()) 0.0 else correctable.size.toDouble() / spellPairs.size

        var fixed = 0
        var miscorrected = 0
        var missed = 0
        var offered = 0
        val latencies = ArrayList<Long>(correctable.size)

        for (pair in correctable) {
            val started = System.nanoTime()
            val result = target.correct(pair.typo)
            latencies += (System.nanoTime() - started) / 1_000

            when {
                result == null -> missed++
                result.equals(pair.correct, ignoreCase = true) -> fixed++
                else -> miscorrected++
            }
            if (target.alternatives(pair.typo, SUGGESTION_LIMIT).any { it.equals(pair.correct, ignoreCase = true) }) {
                offered++
            }
        }

        // Every word of the sentence corpus is, by construction, correctly spelled — so any
        // non-null correction here is damage by definition.
        var typedCorrectly = 0
        var falselyCorrected = 0
        for (sentence in sentences) {
            for (word in sentence) {
                typedCorrectly++
                if (target.correct(word) != null) falselyCorrected++
            }
        }

        var predictionOpportunities = 0
        var nextWordHits = 0
        var completionOpportunities = 0
        var completionHits = 0
        for (sentence in sentences) {
            for (i in 1 until sentence.size) {
                // Two words of left context, matching what KeyboardViewModel actually has
                // available — anything less can never reach the model's trigram tier.
                val beforePrevious = sentence.getOrNull(i - 2)
                val previous = sentence[i - 1]
                val expected = sentence[i]
                predictionOpportunities++
                if (target.suggestNext(beforePrevious, previous, "", TOP_N)
                        .any { it.equals(expected, ignoreCase = true) }
                ) {
                    nextWordHits++
                }
                if (expected.length > COMPLETION_PREFIX) {
                    completionOpportunities++
                    val prefix = expected.take(COMPLETION_PREFIX)
                    if (target.suggestNext(beforePrevious, previous, prefix, TOP_N)
                            .any { it.equals(expected, ignoreCase = true) }
                    ) {
                        completionHits++
                    }
                }
            }
        }

        val n = correctable.size.coerceAtLeast(1)
        return Report(
            target = target.name,
            dictionaryCoverage = coverage,
            correctionAccuracy = fixed.toDouble() / n,
            miscorrectionRate = miscorrected.toDouble() / n,
            missRate = missed.toDouble() / n,
            suggestionRecall = offered.toDouble() / n,
            falseCorrectionRate = falselyCorrected.toDouble() / typedCorrectly.coerceAtLeast(1),
            nextWordRecall = nextWordHits.toDouble() / predictionOpportunities.coerceAtLeast(1),
            completionRecall = completionHits.toDouble() / completionOpportunities.coerceAtLeast(1),
            medianCorrectionMicros = latencies.sorted().getOrElse(latencies.size / 2) { 0L },
        )
    }

    private companion object {
        const val TOP_N = 3
        /** Matches `KeyboardViewModel.SUGGESTION_LIMIT`. */
        const val SUGGESTION_LIMIT = 6
        const val COMPLETION_PREFIX = 2
    }
}
