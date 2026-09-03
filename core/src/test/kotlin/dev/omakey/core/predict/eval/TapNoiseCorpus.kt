package dev.omakey.core.predict.eval

import dev.omakey.core.predict.spatial.KeyboardGeometry
import dev.omakey.core.predict.spatial.TouchTrace
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Simulated touchscreen typing: real words, typed by a finger that misses.
 *
 * The bundled misspelling corpus cannot evaluate the spatial model, because its errors are
 * *cognitive* — "definately" swaps `a` for `i`, which sit on opposite sides of the keyboard, and no
 * amount of geometry makes that substitution look plausible. Real phone typing produces a
 * completely different error distribution, dominated by taps that landed slightly off a key. A
 * model built for the second kind has to be measured on the second kind.
 *
 * Each character is typed by sampling a 2-D Gaussian around its key centre and resolving the result
 * to whichever key is nearest — exactly the pipeline a real keyboard runs, so a sampled tap that
 * strays over a boundary produces a genuine wrong character *and* the coordinate that explains it.
 * That pairing is the point: it lets the same word be scored with and without touch data, isolating
 * what the coordinates are worth.
 *
 * Deterministic by seed, so runs are comparable.
 *
 * **What this is not.** Real touch distributions aren't isotropic Gaussians centred on key centres:
 * users have per-key offsets, thumbs skew along the direction of travel, and error rates rise with
 * speed. Gboard's own spatial-model work exists precisely because the centred-Gaussian assumption
 * is wrong in measurable ways. So this measures whether the decoder *uses* positional evidence
 * correctly, not what accuracy will be on a device. Treat improvements here as directional.
 */
object TapNoiseCorpus {

    /** One simulated typing of one word. */
    class Sample(
        val intended: String,
        val typed: String,
        val taps: TouchTrace.Taps,
        /** Whether the noise actually produced a wrong character. Clean samples matter as much as
         * dirty ones — they measure whether the engine leaves correct typing alone. */
        val corrupted: Boolean,
    )

    /**
     * Types [word] once with taps drawn from a Gaussian of standard deviation [noise], in key
     * widths. Returns null if the word contains anything not on the letter grid.
     *
     * A [noise] of ~0.35 key widths puts roughly one character in ten over a boundary, which is in
     * the range of the 8–9% per-letter error rates reported for touchscreen typing.
     */
    fun type(word: String, noise: Float, random: Random): Sample? {
        val xs = FloatArray(word.length)
        val ys = FloatArray(word.length)
        val typed = StringBuilder(word.length)
        var corrupted = false

        for ((index, character) in word.withIndex()) {
            val center = KeyboardGeometry.centerOf(character) ?: return null
            val x = center.first + (random.gaussian() * noise).toFloat()
            val y = center.second + (random.gaussian() * noise).toFloat()
            xs[index] = x
            ys[index] = y
            val resolved = nearestKey(x, y)
            typed.append(resolved)
            if (resolved != character) corrupted = true
        }

        return Sample(word, typed.toString(), TouchTrace.Taps(xs, ys), corrupted)
    }

    /** Words drawn from the sentence corpus — real text people typed on phones — long enough to be
     * worth correcting and short enough for the engine to consider. */
    fun words(limit: Int, random: Random): List<String> =
        EvalCorpus.sentences()
            .flatten()
            .filter { it.length in 4..12 && it.all { character -> character in 'a'..'z' } }
            .distinct()
            .shuffled(random)
            .take(limit)

    /** What a keyboard without a spatial model sees: the key whose centre is closest to the tap. */
    private fun nearestKey(x: Float, y: Float): Char {
        var best = 'a'
        var bestDistance = Float.MAX_VALUE
        for (character in 'a'..'z') {
            val distance = KeyboardGeometry.squaredDistanceFromPoint(x, y, character)
            if (distance < bestDistance) {
                bestDistance = distance
                best = character
            }
        }
        return best
    }

    /** Box-Muller; `Random` has no Gaussian of its own on the Kotlin standard library. */
    private fun Random.gaussian(): Double {
        var u = nextDouble()
        while (u <= 0.0) u = nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * nextDouble())
    }
}
