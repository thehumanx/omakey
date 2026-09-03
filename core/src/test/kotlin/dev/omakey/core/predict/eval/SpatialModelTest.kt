package dev.omakey.core.predict.eval

import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.PersonalLanguageModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Measures what real tap coordinates are worth, by correcting the *same* simulated typing twice —
 * once with the touch positions and once without.
 *
 * Running both arms over identical samples is what makes the comparison mean anything: the words,
 * the noise and the resulting typos are byte-for-byte the same, so the only variable is whether the
 * decoder can see where the finger landed.
 *
 * See [TapNoiseCorpus] for why the bundled misspelling corpus cannot answer this question, and for
 * the limits of a simulated one.
 *
 * ## Two results, one of them the opposite of what was expected
 *
 * ```
 * noise (key widths)   0.25            0.35            0.45            0.60
 * key identity only    fixed 71.74 %   fixed 53.60 %   fixed 33.82 %   fixed 16.12 %
 *                      wrong 15.81 %   wrong 20.99 %   wrong 25.93 %   wrong 31.07 %
 * with touch points    fixed 71.86 %   fixed 54.01 %   fixed 34.21 %   fixed 16.69 %
 *                      wrong 15.70 %   wrong 20.58 %   wrong 25.54 %   wrong 30.51 %
 * ```
 *
 * **Real coordinates add remarkably little: +0.4 to +0.6 points, consistently, at every noise
 * level.** The reason is visible once stated: when a tap strays over a boundary it lands on a key
 * *adjacent* to the intended one, and `KeyboardGeometry` already knows those two keys are adjacent.
 * The coordinate refines a cost the static approximation had roughly right. It is a real gain and
 * it points the right way — but it does not justify threading tap positions through the typing hot
 * path, with the misalignment failure modes that brings (paste, cursor moves, hardware keyboards,
 * accent popups). The plumbing is deliberately *not* wired; `ChannelModel.substitutionAt` accepts
 * taps and is exercised here, so switching it on later is a change at the call site.
 *
 * The genuine spatial win in the literature is not raw coordinates but *personalised* per-key
 * offsets — most people's taps are systematically off-centre, in their own direction, per key.
 * That is what Gboard's spatial-model work is about, and this simulation cannot show it because it
 * generates taps centred on the key by construction.
 *
 * **The more useful result is the absolute one.** On simulated touchscreen typing the engine fixes
 * 54% and breaks 21% at moderate noise, and 72% against 16% at light noise — fixing three to four
 * times more often than it errs. The cognitive-misspelling scorecard in [EngineEvaluationTest]
 * shows the reverse (33% fixed against 36% wrong), and it was tempting to read that as the engine
 * being broken. It is measuring a different, much harder problem: "definately" is four edits and a
 * cross-keyboard substitution from its target, and a phone keyboard is not mainly in that business.
 *
 * Note the "damage on clean words" column is not comparable with the false-correction rate in
 * [EngineEvaluationTest]: [TapNoiseCorpus.words] samples *distinct* words, so rare vocabulary is
 * hugely over-represented relative to how often it is actually typed.
 */
class SpatialModelTest {

    private val index = AutocorrectIndex().apply { load(TestLanguageModel.load(), PersonalLanguageModel()) }

    private class Arm(val label: String) {
        var fixed = 0
        var wrong = 0
        var missed = 0
        var damaged = 0
        var clean = 0

        fun report(corrupted: Int) = String.format(
            "%-24s fixed=%5.2f%%  wrong=%5.2f%%  missed=%5.2f%%   damage on clean words=%5.2f%%",
            label, pct(fixed, corrupted), pct(wrong, corrupted), pct(missed, corrupted), pct(damaged, clean),
        )

        private fun pct(count: Int, total: Int) = if (total == 0) 0.0 else 100.0 * count / total
    }

    /** Opt-in (`-Domakey.tune=true`) — four extra full passes, and the headline conclusion is
     * already recorded in this class's KDoc. */
    @Test
    fun `how much are touch coordinates worth across noise levels`() {
        assumeTrue("set -Domakey.tune=true to run the sweep", System.getProperty("omakey.tune") == "true")
        // Swept rather than measured at one point: the value of a coordinate depends on how far
        // taps stray, and a single noise level could flatter or bury the effect.
        for (noise in floatArrayOf(0.25f, 0.35f, 0.45f, 0.6f)) {
            runArms(noise, assertions = false)
        }
    }

    @Test
    fun `touch coordinates improve correction of simulated typing`() {
        runArms(NOISE, assertions = true)
    }

    private fun runArms(noise: Float, assertions: Boolean) {
        val random = Random(SEED)
        val words = TapNoiseCorpus.words(WORD_COUNT, random)

        val withTaps = Arm("with touch positions")
        val withoutTaps = Arm("key identity only")
        var corrupted = 0
        var clean = 0

        for (word in words) {
            val sample = TapNoiseCorpus.type(word, noise, random) ?: continue
            if (sample.typed == sample.intended) {
                clean++
                // A correctly-typed word must be left alone; anything else is damage the user has
                // to notice and undo.
                if (index.correct(sample.typed, taps = sample.taps) != null) withTaps.damaged++
                if (index.correct(sample.typed) != null) withoutTaps.damaged++
                continue
            }
            corrupted++
            score(withTaps, index.correct(sample.typed, taps = sample.taps), sample.intended)
            score(withoutTaps, index.correct(sample.typed), sample.intended)
        }

        withTaps.clean = clean
        withoutTaps.clean = clean

        println("=".repeat(96))
        println("simulated touchscreen typing — $corrupted corrupted / $clean clean, noise=$noise key widths")
        println("=".repeat(96))
        println(withoutTaps.report(corrupted))
        println(withTaps.report(corrupted))
        println("=".repeat(96))

        if (!assertions) return
        assertTrue("no corrupted samples were generated — the noise model is broken", corrupted > 100)
        assertTrue(
            "touch positions should fix more than key identity alone " +
                "(${withTaps.fixed} vs ${withoutTaps.fixed})",
            withTaps.fixed > withoutTaps.fixed,
        )
        assertTrue(
            "touch positions should not make wrong corrections more common " +
                "(${withTaps.wrong} vs ${withoutTaps.wrong})",
            withTaps.wrong <= withoutTaps.wrong,
        )
    }

    private fun score(arm: Arm, result: String?, intended: String) {
        when {
            result == null -> arm.missed++
            result.equals(intended, ignoreCase = true) -> arm.fixed++
            else -> arm.wrong++
        }
    }

    private companion object {
        const val SEED = 20260903L
        const val WORD_COUNT = 4_000

        /** Standard deviation of the tap distribution, in key widths. Chosen to put roughly one
         * character in ten over a key boundary, in the range of published per-letter error rates
         * for touchscreen typing. */
        const val NOISE = 0.35f
    }
}
