package dev.omakey.core.predict.eval

import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.NgramPredictionEngine
import dev.omakey.core.predict.PersonalLanguageModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Pins the English engine's exact output, so the multi-language refactor (AGENTS.md §66, phases
 * 1–4) can prove it changed nothing for English.
 *
 * The aggregate scorecards ([EngineEvaluationTest], [SpatialModelTest]) are the wrong tool for
 * that: they have regression *floors*, so a refactor that quietly swaps one right answer for a
 * different right answer — or trades a fix here for a miscorrection there — passes them. This test
 * fails on any difference at all, which is the property "a pure refactor" actually claims.
 *
 * Covers every path the refactor touches: plain correction and alternatives (the alphabet and
 * first-letter logic), correction from simulated taps (the geometry, which moves from a hardcoded
 * table to one derived from the layout), and next-word / completion prediction with context (the
 * language model reader, which gets a new on-disk format).
 *
 * **Regenerating** is a deliberate act, never a fix for a red test:
 * `./gradlew :core:testDebugUnitTest -Domakey.golden.update=true --tests '*GoldenBehaviourTest'`,
 * and the commit that does it says why the English behaviour was *meant* to change.
 */
class GoldenBehaviourTest {

    private val model = TestLanguageModel.load()
    private val personal = PersonalLanguageModel()
    private val index = AutocorrectIndex().apply { load(model, personal) }
    private val engine = NgramPredictionEngine(model, InMemoryWordDao(), personal)

    @Test
    fun englishBehaviourIsUnchanged() {
        val actual = render()
        val file = goldenFile()
        if (System.getProperty("omakey.golden.update") == "true" || !file.isFile) {
            val existed = file.isFile
            file.parentFile.mkdirs()
            file.writeText(actual)
            if (!existed) fail("Golden file did not exist; wrote ${file.path}. Review it, commit it, rerun.")
            return
        }
        val expected = file.readText()
        if (expected == actual) return
        // Report the first few differing lines rather than two multi-thousand-line blobs.
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val diffs = expectedLines.indices.union(actualLines.indices).asSequence()
            .filter { expectedLines.getOrNull(it) != actualLines.getOrNull(it) }
            .take(10)
            .joinToString("\n") { "  line ${it + 1}\n    expected: ${expectedLines.getOrNull(it)}\n    actual:   ${actualLines.getOrNull(it)}" }
        fail("English engine output changed (${expectedLines.size} -> ${actualLines.size} lines). First differences:\n$diffs")
    }

    private fun render(): String = buildString {
        for (pair in EvalCorpus.spellErrors(limit = 800)) {
            val typo = pair.typo
            appendLine("correct\t$typo\t${index.correct(typo)}")
            appendLine("alts\t$typo\t${index.alternatives(typo, limit = 6).joinToString(",")}")
        }

        // Fixed seed: the samples themselves have to be identical run to run.
        val random = Random(66)
        for (word in TapNoiseCorpus.words(limit = 400, random = random)) {
            val sample = TapNoiseCorpus.type(word, noise = 0.35f, random = random) ?: continue
            appendLine("taps\t${sample.typed}\t${index.correct(sample.typed, taps = sample.taps)}")
            appendLine("taps-alts\t${sample.typed}\t${index.alternatives(sample.typed, limit = 6, taps = sample.taps).joinToString(",")}")
        }

        runBlocking {
            for (sentence in EvalCorpus.sentences(limit = 250)) {
                for (i in 1 until sentence.size) {
                    val before = sentence.getOrNull(i - 2)
                    val previous = sentence[i - 1]
                    val word = sentence[i]
                    val context = index.contextOf(previous, before)
                    appendLine("next\t$before $previous\t${engine.suggestNext(before, previous, "", 3).joinToString(",")}")
                    if (word.length >= 3) {
                        val prefix = word.take(2)
                        appendLine("complete\t$before $previous $prefix\t${engine.suggestNext(before, previous, prefix, 3).joinToString(",")}")
                        appendLine("correct-ctx\t$previous $word\t${index.correct(word, context)}")
                    }
                }
            }
        }
    }

    private fun goldenFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "core/src/test").isDirectory) return File(dir, "core/src/test/resources/golden/en_us.txt")
            dir = dir.parentFile
        }
        error("core/src/test not found above ${File("").absolutePath}")
    }
}
