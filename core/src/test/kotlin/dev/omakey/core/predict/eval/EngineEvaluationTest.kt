package dev.omakey.core.predict.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prints the engine scorecard and guards the floors it establishes.
 *
 * Not a pass/fail unit test in the usual sense — its output is the deliverable. Assertions here
 * are deliberately loose regression floors well below the measured values, so this fails when a
 * change makes the engine *substantially* worse rather than pinning exact numbers that would turn
 * every legitimate tuning change into a test edit.
 *
 * Sample sizes are capped so a normal `:core:test` run stays quick; pass
 * `-Domakey.eval.full=true` to run the complete corpora when tuning.
 *
 * ## Scorecard by phase
 *
 * ```
 *                                        baseline   phase 1   phase 2
 * dictionary coverage of intended words    96.50 %   99.10 %   99.10 %
 * CORRECTION
 *   fixed correctly                        18.34 %   25.38 %   32.67 %
 *   changed to the wrong word              35.98 %   34.64 %   36.38 %
 *   left uncorrected                       45.67 %   39.98 %   30.95 %
 *   intended word offered in strip         38.60 %   42.73 %   55.20 %
 * DAMAGE
 *   falsely corrected                       0.40 %    0.63 %    0.73 %
 * PREDICTION
 *   next word in top 3 (no prefix)          7.82 %   25.28 %   25.28 %
 *   next word in top 3 (2-char prefix)     53.79 %   62.67 %   62.67 %
 * median correct() latency                    171 us    163 us    283 us
 * ```
 *
 * **Baseline** measured the engine as shipped: a rank-seeded SQLite dictionary whose bigram table
 * had been written out in alphabetical order. The two numbers that gave the game away were
 * next-word recall of 7.8% against 53.8% for the *same word* once two characters were typed —
 * completion read the word list, which was genuinely frequency-ordered, while next-word read the
 * bigram table, which was not — and miscorrection (36%) running at double accuracy (18%).
 *
 * **Phase 1** replaced the data and the scoring: a curated 76k-word vocabulary with real
 * interpolated tri/bi/unigram probabilities, memory-mapped instead of imported into SQLite.
 * Next-word recall roughly tripled, which is the alphabetical-ordering bug being paid back
 * directly. Correction accuracy rose 38% relative, mostly because the vocabulary no longer
 * *contains* the misspellings — "teh", "recieve" and "definately" were all previously "known
 * words", and a word the model believes is real is a word autocorrect refuses to touch.
 *
 * The false-correction rate rising slightly (0.40% → 0.63%) is the expected shape of this
 * trade-off: an engine that corrects more will occasionally correct something it shouldn't. It
 * stays far below the level users notice, and is worth watching rather than acting on.
 *
 * **Phase 2** replaced lexicographic `(edit distance, then frequency)` ranking with one
 * noisy-channel score, `-channelCost + λ·logP(candidate | context)`, where the channel term knows
 * where the keys are. Accuracy rose another 29% relative and strip recall by 29%, at ~280µs — two
 * orders of magnitude inside the 20ms budget a keypress has.
 *
 * Two hypotheses were tested and **refuted** along the way, both of which had been written into the
 * plan as fact:
 *
 *  - *"First-letter pruning is a correctness ceiling."* Removing it entirely made accuracy slightly
 *    **worse** (32.4% → 31.3%) and miscorrection worse (36.6% → 39.4%), while costing 14× the
 *    latency (314µs → 4.6ms). The extra candidates are overwhelmingly unrelated words that happen
 *    to fall within two edits, and they win often enough to do net harm. The original design
 *    comment defending this prune was right.
 *  - *"Ranking is the bottleneck."* [RecallDiagnosticTest] attributes the failures: only 53% of
 *    intended words are reachable at all, and the dominant cause is **edit distance above 2**
 *    (30.3%), far ahead of the frequency floor (7.5%) or a mistyped first letter (8.2%). Ranking
 *    can only choose among what the search reaches.
 *
 * Raising the bound to three edits was then measured rather than assumed: it finds 1.6 points more
 * correct answers and produces 9.9 points more wrong ones. So the bound stays at 2 for silent
 * auto-apply and is 3 for the browsable strip, where the user adjudicates — the same evidence
 * pointing opposite ways for two paths with different costs of being wrong.
 *
 * **Still outstanding**: miscorrection (36.4%) remains higher than accuracy (32.7%). The
 * diagnostic says most of the remaining gap is misspellings 3+ edits from their target, which a
 * keyboard arguably *should not* silently correct. Note also what this corpus cannot measure: it
 * contains **cognitive** misspellings, where a geometry-aware channel is near-useless because
 * "definately" substitutes `a` for `i` from opposite sides of the keyboard. On a real device the
 * error distribution is dominated by near-miss taps, which is exactly what the channel model
 * prices well — so the remaining work is feeding it real touch coordinates, not widening the
 * search further.
 */
class EngineEvaluationTest {

    @Test
    fun `engine scorecard`() = runBlocking {
        val full = System.getProperty("omakey.eval.full") == "true"
        val evaluator = EngineEvaluator(
            spellPairs = EvalCorpus.spellErrors(limit = if (full) Int.MAX_VALUE else SPELL_PAIR_SAMPLE),
            sentences = EvalCorpus.sentences(limit = if (full) Int.MAX_VALUE else SENTENCE_SAMPLE),
        )

        val report = evaluator.evaluate(CurrentEngineTarget())
        println(report.format())

        assertTrue(
            "Suggestion-strip recall regressed to ${report.suggestionRecall} (phase 2: 0.5520).",
            report.suggestionRecall > 0.52,
        )
        assertTrue(
            "Dictionary coverage collapsed to ${report.dictionaryCoverage} — the bundled wordlist " +
                "is missing or truncated.",
            report.dictionaryCoverage > 0.5,
        )
        // Floors sit just under the recorded baseline, not at some aspirational target: their job
        // is to catch a regression, and a floor above the current value would just fail forever.
        // Raise them as each phase lands.
        assertTrue(
            "Correction accuracy regressed to ${report.correctionAccuracy} (phase 2: 0.3267).",
            report.correctionAccuracy > 0.30,
        )
        assertTrue(
            "Next-word recall regressed to ${report.nextWordRecall} (phase 2: 0.2528).",
            report.nextWordRecall > 0.22,
        )
        assertTrue(
            "False-correction rate rose to ${report.falseCorrectionRate} (phase 2: 0.0073) — " +
                "correctly-typed words are being mangled.",
            report.falseCorrectionRate < 0.015,
        )
    }

    private companion object {
        const val SPELL_PAIR_SAMPLE = 4_000
        const val SENTENCE_SAMPLE = 400
    }
}
