package dev.omakey.core.predict.eval

import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.pack.PackInstaller
import dev.omakey.core.db.WordEntity
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.NgramPredictionEngine
import dev.omakey.core.predict.PersonalLanguageModel
import dev.omakey.core.predict.lm.LanguageModel
import dev.omakey.core.predict.spatial.KeyboardGeometry
import dev.omakey.core.locale.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Normalizer
import kotlin.random.Random

/**
 * Scores a built language pack (`scripts/build_pack.py`) the way [EngineEvaluationTest] scores
 * English — installed through the real [PackInstaller], corrected by the real [AutocorrectIndex]
 * with the pack's own profile and a geometry derived from its own layout.
 *
 * Skipped unless `build/packs/<id>-*.zip` exists: packs are build outputs, not committed.
 *
 * Measured on Tatoeba sentences held out of training (`test/resources/eval/<id>/sentences.txt`):
 *  - **Accent restoration**, split in two because they are different jobs. A stripped form that is
 *    not a word ("tambien") is autocorrect's to fix silently. One that *is* a word ("esta" for
 *    "está", "si" for "sí") never is — rewriting a real word needs the user's say-so — so there the
 *    question is whether the suggestion strip offers the accented form.
 *  - **Touch noise** — words typed with Gaussian tap noise on the pack's layout (0.35 key widths).
 *  - **Damage** — correctly typed words that correction changes anyway.
 *
 * Spanish, 2026-09-24 (pack 1.0.0, 150,000 words, 4.6 MB):
 *   accent restoration, non-words    fixed 91.8 %   wrong 6.8 %
 *   accent restoration, real words   offered in the strip 79.2 %
 *   touch noise 0.35                 fixed 61.1 %   wrong 6.0 %
 *   correct words damaged            0.54 %
 *
 * French, same date (pack 1.0.0, 150,000 words, 5.6 MB, AZERTY):
 *   accent restoration, non-words    fixed 87.4 %   wrong 9.3 %
 *   touch noise 0.35                 fixed 57.8 %   wrong 6.0 %
 *   correct words damaged            0.19 %
 *
 * For comparison, English on its own tap-noise simulation is 66 % / 13 % with 0.73 % damage (§51.1).
 * The remaining accent misses are mostly vocabulary coverage (rare verb forms, proper names).
 * Floors below sit just under these.
 */
class LanguagePackEvaluationTest {

    @get:Rule val temp = TemporaryFolder()

    private fun packZip(id: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val packs = File(dir, "build/packs")
            packs.listFiles()?.filter { it.name.startsWith("$id-") && it.name.endsWith(".zip") }?.maxByOrNull { it.name }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun sentences(id: String): List<List<String>> {
        val url = javaClass.classLoader!!.getResource("eval/$id/sentences.txt") ?: return emptyList()
        return File(url.toURI()).readLines().filter { it.isNotBlank() }.map { it.trim().split(' ') }
    }

    private class Engine(val locale: KeyboardLocale) {
        val model = LanguageModel.load(File((locale.languageModel as ModelSource.File).path))
        val geometry = KeyboardGeometry.from(locale.letterLayout, locale.profile::isWordChar)
        val index = AutocorrectIndex().apply { load(model, PersonalLanguageModel(), locale.profile, geometry) }
        val prediction = NgramPredictionEngine(model, InMemoryWordDao(), PersonalLanguageModel(), locale.id, locale.profile)
    }

    private fun install(id: String): Engine {
        val zip = packZip(id)
        assumeTrue("no built $id pack in build/packs", zip != null)
        return Engine(PackInstaller(temp.newFolder("languages")).install(zip!!))
    }

    private fun stripAccents(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    data class Score(val total: Int, val fixed: Int, val wrong: Int) {
        val fixedRate get() = 100.0 * fixed / total
        val wrongRate get() = 100.0 * wrong / total
        override fun toString() = "n=$total fixed %.1f %% wrong %.1f %%".format(fixedRate, wrongRate)
    }

    private fun measure(engine: Engine, sentences: List<List<String>>, corrupt: (String) -> String?): Score {
        var total = 0; var fixed = 0; var wrong = 0
        for (sentence in sentences) {
            for (i in sentence.indices) {
                val word = sentence[i]
                val typed = corrupt(word) ?: continue
                if (typed == word) continue
                total++
                val context = engine.index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2))
                when (engine.index.correct(typed, context) ?: typed) {
                    word -> fixed++
                    typed -> Unit
                    else -> wrong++
                }
            }
        }
        return Score(total, fixed, wrong)
    }

    @Test
    fun spanish() {
        val engine = install("es_ES")
        val sentences = sentences("es_ES")
        assumeTrue("no held-out Spanish sentences", sentences.isNotEmpty())

        // Behavioural cases — the ones a Spanish typist would notice first.
        val index = engine.index
        assertEquals("también", index.correct("tambien"))
        assertEquals("canción", index.correct("cancion"))
        assertEquals("después", index.correct("despues"))
        assertTrue(index.alternatives("porfavor", 3).contains("por favor"))
        assertEquals(null, index.correct("esta")) // a real word: never touched, "está" only offered
        assertTrue(index.alternatives("esta", 6).contains("está"))

        // Split, because they are different jobs: a stripped form that is not a word ("tambien") is
        // autocorrect's to fix silently; one that is itself a word ("esta" for "está", "si" for
        // "sí") never is — changing a real word needs the user's say-so — so there the question is
        // whether the strip offers it.
        val accents = measure(engine, sentences) { w -> stripAccents(w).takeIf { it != w && !index.isKnown(it) } }
        var pairTotal = 0; var pairOffered = 0
        val misses = mutableListOf<String>()
        for (sentence in sentences) for (i in sentence.indices) {
            val word = sentence[i]
            val stripped = stripAccents(word)
            if (stripped == word || !index.isKnown(stripped)) continue
            pairTotal++
            val context = index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2))
            if (index.alternatives(stripped, 6, context).contains(word)) pairOffered++
        }
        for (sentence in sentences.take(400)) for (i in sentence.indices) {
            val word = sentence[i]; val stripped = stripAccents(word)
            if (stripped == word || index.isKnown(stripped)) continue
            val got = index.correct(stripped, index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2)))
            if (got != word && misses.size < 40) misses += "$stripped->$got (want $word)"
        }
        println("es_ES misses: $misses")
        println("es_ES accent pairs offered in strip: %.1f %% of %d".format(100.0 * pairOffered / pairTotal, pairTotal))

        val random = Random(66)
        val noise = measure(engine, sentences.take(1500)) { w -> typeWithNoise(engine.geometry, w, 0.35f, random) }

        var clean = 0; var damaged = 0
        for (sentence in sentences) for (i in sentence.indices) {
            val word = sentence[i]
            if (word.length < 3) continue
            clean++
            val context = index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2))
            val result = index.correct(word, context)
            if (result != null && result != word) damaged++
        }
        val damage = 100.0 * damaged / clean

        println("es_ES accent restoration: $accents")
        println("es_ES touch noise 0.35:  $noise")
        println("es_ES correct words damaged: %.2f %% of %d".format(damage, clean))

        val offeredRate = 100.0 * pairOffered / pairTotal
        assertTrue("accent restoration regressed: $accents", accents.fixedRate >= 89.0 && accents.wrongRate <= 8.5)
        assertTrue("accented real words stopped being offered: $offeredRate", offeredRate >= 76.0)
        assertTrue("touch-noise correction regressed: $noise", noise.fixedRate >= 58.0 && noise.wrongRate <= 8.0)
        assertTrue("damage to correct words regressed: $damage", damage <= 0.7)
    }

    @Test
    fun french() {
        val engine = install("fr_FR")
        val sentences = sentences("fr_FR")
        assumeTrue("no held-out French sentences", sentences.isNotEmpty())
        val index = engine.index

        assertEquals("azerty_fr", engine.locale.letterLayout.id)
        assertEquals(listOf("azerty_fr", "qwerty_fr"), engine.locale.letterLayoutChoices.map { it.id })
        assertEquals("qwerty_fr", engine.locale.withLetterLayout("qwerty_fr").letterLayout.id)

        assertEquals("très", index.correct("tres"))
        assertEquals("être", index.correct("etre"))
        assertEquals("français", index.correct("francais"))
        // Elision: the word after the clitic is corrected, with the clitic as its context.
        assertEquals("l'homme", index.correct("l'homne"))
        assertEquals("L'homme", index.correct("L'homne")) // the clitic keeps its typed case
        assertEquals(null, index.correct("aujourd'hui"))
        assertTrue(index.isKnown("c'est"))
        assertTrue(index.alternatives("cest", 3).contains("c'est"))
        kotlinx.coroutines.runBlocking {
            assertTrue(engine.prediction.suggestNext(null, "je", "", 5).contains("suis"))
            assertTrue(engine.prediction.suggestNext(null, null, "l'hom", 5).contains("l'homme"))
            assertTrue(engine.prediction.suggestNext(null, "c'est", "", 10).isNotEmpty())
        }

        val accents = measure(engine, sentences) { w -> stripAccents(w).takeIf { it != w && !index.isKnown(it) } }
        val random = Random(66)
        val noise = measure(engine, sentences.take(1500)) { w -> typeWithNoise(engine.geometry, w, 0.35f, random) }
        var clean = 0; var damaged = 0
        for (sentence in sentences) for (i in sentence.indices) {
            val word = sentence[i]
            if (word.length < 3) continue
            clean++
            val result = index.correct(word, index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2)))
            if (result != null && result != word) damaged++
        }
        val damage = 100.0 * damaged / clean
        println("fr_FR accent restoration: $accents")
        println("fr_FR touch noise 0.35 (AZERTY): $noise")
        println("fr_FR correct words damaged: %.2f %% of %d".format(damage, clean))

        assertTrue("accent restoration regressed: $accents", accents.fixedRate >= 85.0 && accents.wrongRate <= 11.0)
        assertTrue("touch-noise correction regressed: $noise", noise.fixedRate >= 54.0 && noise.wrongRate <= 8.0)
        assertTrue("damage to correct words regressed: $damage", damage <= 0.4)
    }

    @Test
    fun nepali() {
        val engine = install("ne_NP")
        val locale = engine.locale
        assertEquals("qwerty_ne", locale.letterLayout.id)
        assertTrue(locale.letterLayout.transliteration)
        val devanagari = locale.withLetterLayout("devanagari_ne").letterLayout
        assertEquals("devanagari_ne_shift", devanagari.shiftLayoutId)
        assertTrue(locale.extraLayouts.any { it.id == "devanagari_ne_shift" })
        assertEquals(false, locale.profile.autoApplyCorrections)
        assertEquals('।', locale.profile.doubleSpaceInserts)
        // A conjunct on long-press commits all three code points.
        assertTrue(devanagari.rows.flatMap { it.keys }.single { it.label == "क" }.popupChars.contains("क्ष"))

        // The transliteration index builds inside the installed pack, as the keyboard builds it.
        val packDir = File((locale.languageModel as ModelSource.File).path).parentFile
        val index = dev.omakey.core.translit.TransliterationIndex.openOrBuild(File(packDir, "translit.idx"), engine.model)
        val transliterator = dev.omakey.core.translit.Transliterator(engine.model, index)
        assertTrue("नमस्ते" in transliterator.candidates("namaste", limit = 3))
        assertTrue(File(packDir, "translit.idx").isFile)

        // Devanagari autocorrect: व for ब is an equivalent-letter slip, and the model knows किताब.
        val geometry = KeyboardGeometry.from(devanagari, locale.profile::isWordChar)
        val index2 = AutocorrectIndex().apply { load(engine.model, PersonalLanguageModel(), locale.profile, geometry) }
        assertTrue(index2.isKnown("किताब"))
        assertTrue(index2.alternatives("किताव", 3).contains("किताब"))
        assertTrue(index2.alternatives("तिमि", 3).contains("तिमी"))
    }

    /** [word] typed with Gaussian tap noise on [geometry], resolved to the nearest key; null if the
     * word has a character that isn't a key. */
    private fun typeWithNoise(geometry: KeyboardGeometry, word: String, noise: Float, random: Random): String? {
        val keys = geometry.keys
        val out = StringBuilder()
        for (c in word) {
            val (cx, cy) = geometry.centerOf(c) ?: return null
            val x = cx + gaussian(random) * noise
            val y = cy + gaussian(random) * noise
            out.append(keys.minBy { geometry.squaredDistanceFromPoint(x, y, it) })
        }
        return out.toString()
    }

    private fun gaussian(random: Random): Float {
        val u = 1.0 - random.nextDouble()
        val v = random.nextDouble()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat()
    }
}
