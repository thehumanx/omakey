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
 * Portuguese (Brazil), 2026-09-26 (pack 1.0.0, 150,000 words, 4.6 MB):
 *   accent restoration, non-words    fixed 93.3 %   wrong 2.8 %
 *   accent restoration, real words   offered in the strip 50.8 %
 *   touch noise 0.35                 fixed 63.9 %   wrong 7.2 %
 *   correct words damaged            0.38 %
 * The lower "offered" figure is largely "e"/"é" and "a"/"à": roughly two in five of these pairs, and
 * alternatives() never considers them because it skips words under three letters.
 *
 * Italian, 2026-10-02 (pack 1.0.0, 150,000 words, 6.0 MB):
 *   accent restoration, non-words    fixed 95.2 %   wrong 4.8 %
 *   touch noise 0.35                 fixed 62.3 %   wrong 7.0 %
 *   correct words damaged            0.16 %
 *
 * Russian, same date (pack 1.0.0, 200,000 words, 7.3 MB):
 *   touch noise 0.35, ЙЦУКЕН         fixed 60.3 %   wrong 7.9 %
 *   touch noise 0.35, phonetic       fixed 60.0 %   wrong 7.9 %
 *   correct words damaged            0.35 %
 *   translit, words as romanized     top-1 98.2 %   top-3 99.7 %
 *   translit, casual spellings       top-3 94.8 % (х as "h", щ "sch", ц "c", й "j", ё "e", ы "i")
 * The translit figures are optimistic: the typing is generated from the same romanization the
 * index is keyed on, so they measure the folding and ranking, not how varied real typing is.
 * Remaining misses are mostly "-tsya" verb endings and words outside the vocabulary.
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

    private fun measure(
        engine: Engine,
        sentences: List<List<String>>,
        index: AutocorrectIndex = engine.index,
        corrupt: (String) -> String?,
    ): Score {
        var total = 0; var fixed = 0; var wrong = 0
        for (sentence in sentences) {
            for (i in sentence.indices) {
                val word = sentence[i]
                val typed = corrupt(word) ?: continue
                if (typed == word) continue
                total++
                val context = index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2))
                when (index.correct(typed, context) ?: typed) {
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
    fun portuguese() {
        val engine = install("pt_BR")
        val sentences = sentences("pt_BR")
        assumeTrue("no held-out Portuguese sentences", sentences.isNotEmpty())
        val index = engine.index

        assertEquals("não", index.correct("nao"))
        assertEquals("você", index.correct("voce"))
        assertEquals("também", index.correct("tambem"))
        assertEquals("coração", index.correct("coracao"))
        assertEquals(null, index.correct("esta")) // a real word ("this"): "está" is only offered
        assertTrue(index.alternatives("esta", 6).contains("está"))
        kotlinx.coroutines.runBlocking {
            assertTrue(engine.prediction.suggestNext(null, null, "obrig", 3).contains("obrigado"))
        }

        val accents = measure(engine, sentences) { w -> stripAccents(w).takeIf { it != w && !index.isKnown(it) } }
        var pairTotal = 0; var pairOffered = 0
        for (sentence in sentences) for (i in sentence.indices) {
            val word = sentence[i]
            val stripped = stripAccents(word)
            if (stripped == word || !index.isKnown(stripped)) continue
            pairTotal++
            val context = index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2))
            if (index.alternatives(stripped, 6, context).contains(word)) pairOffered++
        }
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
        val offeredRate = 100.0 * pairOffered / pairTotal
        println("pt_BR accent restoration: $accents")
        println("pt_BR accent pairs offered in strip: %.1f %% of %d".format(offeredRate, pairTotal))
        println("pt_BR touch noise 0.35:  $noise")
        println("pt_BR correct words damaged: %.2f %% of %d".format(damage, clean))

        assertTrue("accent restoration regressed: $accents", accents.fixedRate >= PT_ACCENT_FIXED && accents.wrongRate <= PT_ACCENT_WRONG)
        assertTrue("accented real words stopped being offered: $offeredRate", offeredRate >= PT_OFFERED)
        assertTrue("touch-noise correction regressed: $noise", noise.fixedRate >= PT_NOISE_FIXED && noise.wrongRate <= PT_NOISE_WRONG)
        assertTrue("damage to correct words regressed: $damage", damage <= PT_DAMAGE)
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
        val index = dev.omakey.core.translit.TransliterationIndex.openOrBuild(File(packDir, "translit.idx"), engine.model, dev.omakey.core.translit.NepaliScheme)
        val transliterator = dev.omakey.core.translit.Transliterator(engine.model, index, dev.omakey.core.translit.NepaliScheme)
        assertTrue("नमस्ते" in transliterator.candidates("namaste", limit = 3))
        assertTrue(File(packDir, "translit.idx").isFile)

        // Devanagari autocorrect: व for ब is an equivalent-letter slip, and the model knows किताब.
        val geometry = KeyboardGeometry.from(devanagari, locale.profile::isWordChar)
        val index2 = AutocorrectIndex().apply { load(engine.model, PersonalLanguageModel(), locale.profile, geometry) }
        assertTrue(index2.isKnown("किताब"))
        assertTrue(index2.alternatives("किताव", 3).contains("किताब"))
        assertTrue(index2.alternatives("तिमि", 3).contains("तिमी"))
    }

    @Test
    fun italian() {
        val engine = install("it_IT")
        val sentences = sentences("it_IT")
        assumeTrue("no held-out Italian sentences", sentences.isNotEmpty())
        val index = engine.index

        assertEquals("perché", index.correct("perche"))
        assertEquals("più", index.correct("piu"))
        assertEquals("città", index.correct("citta"))
        assertEquals("perché", index.correct("perchè")) // the wrong accent, not just a missing one
        assertEquals("l'uomo", index.correct("l'uomi")) // elided article kept, the rest corrected
        assertEquals(null, index.correct("e")) // "e" and "è" are both words: never forced
        kotlinx.coroutines.runBlocking {
            assertTrue(engine.prediction.suggestNext(null, "c'", "", 3).contains("è"))
        }

        val accents = measure(engine, sentences) { w -> stripAccents(w).takeIf { it != w && !index.isKnown(it) } }
        val random = Random(66)
        val noise = measure(engine, sentences.take(1500)) { w -> typeWithNoise(engine.geometry, w, 0.35f, random) }
        val damage = damage(engine, sentences)
        println("it_IT accent restoration: $accents")
        println("it_IT touch noise 0.35:  $noise")
        println("it_IT correct words damaged: %.2f %%".format(damage))

        assertTrue("accent restoration regressed: $accents", accents.fixedRate >= IT_ACCENT_FIXED && accents.wrongRate <= IT_ACCENT_WRONG)
        assertTrue("touch-noise correction regressed: $noise", noise.fixedRate >= IT_NOISE_FIXED && noise.wrongRate <= IT_NOISE_WRONG)
        assertTrue("damage to correct words regressed: $damage", damage <= IT_DAMAGE)
    }

    @Test
    fun russian() {
        val engine = install("ru_RU")
        val sentences = sentences("ru_RU")
        assumeTrue("no held-out Russian sentences", sentences.isNotEmpty())
        val locale = engine.locale
        assertEquals("jcuken_ru", locale.letterLayout.id)
        assertEquals(listOf("jcuken_ru", "phonetic_ru", "translit_ru"), locale.letterLayoutChoices.map { it.id })
        assertTrue(locale.withLetterLayout("translit_ru").letterLayout.transliteration)
        assertEquals(dev.omakey.core.locale.Script.CYRILLIC, locale.profile.script)
        val index = engine.index

        assertEquals("привет", index.correct("привер")) // т/р are neighbours on ЙЦУКЕН
        assertEquals("спасибо", index.correct("спасиьо")) // б/ь are neighbours too; the view model restores case
        assertTrue(index.isKnown("ещё") && index.isKnown("еще"))
        assertEquals(null, index.correct("еще")) // ё is optional in Russian: never forced
        kotlinx.coroutines.runBlocking {
            assertTrue(engine.prediction.suggestNext(null, "доброе", "", 3).contains("утро"))
        }

        val random = Random(66)
        val noise = measure(engine, sentences.take(1500)) { w -> typeWithNoise(engine.geometry, w, 0.35f, random) }
        val phonetic = locale.withLetterLayout("phonetic_ru").letterLayout
        val phoneticGeometry = KeyboardGeometry.from(phonetic, locale.profile::isWordChar)
        val phoneticEngine = AutocorrectIndex().apply { load(engine.model, PersonalLanguageModel(), locale.profile, phoneticGeometry) }
        val phoneticNoise = measure(engine, sentences.take(1500), phoneticEngine) { w -> typeWithNoise(phoneticGeometry, w, 0.35f, random) }
        val damage = damage(engine, sentences)
        println("ru_RU touch noise 0.35 (ЙЦУКЕН):  $noise")
        println("ru_RU touch noise 0.35 (phonetic): $phoneticNoise")
        println("ru_RU correct words damaged: %.2f %%".format(damage))

        // Transliteration, built inside the installed pack as the keyboard builds it.
        val packDir = File((locale.languageModel as ModelSource.File).path).parentFile
        val scheme = dev.omakey.core.translit.RussianScheme
        val translitIndex = dev.omakey.core.translit.TransliterationIndex.openOrBuild(File(packDir, "translit.idx"), engine.model, scheme)
        val transliterator = dev.omakey.core.translit.Transliterator(engine.model, translitIndex, scheme)
        var top1 = 0; var top3 = 0; var total = 0
        val misses = ArrayList<String>()
        for (sentence in sentences.take(400)) for (i in sentence.indices) {
            val word = sentence[i]
            val typed = scheme.romanize(word)
            if (typed.isEmpty()) continue
            total++
            val context = transliterator.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2))
            val candidates = transliterator.candidates(typed, context, 3)
            if (candidates.firstOrNull() == word) top1++
            if (word in candidates) top3++ else if (misses.size < 30) misses += "$typed→$word $candidates"
        }
        val top1Rate = 100.0 * top1 / total
        val top3Rate = 100.0 * top3 / total
        println("ru_RU translit (romanized held-out words): top-1 %.1f %%, top-3 %.1f %% of %d".format(top1Rate, top3Rate, total))
        println("ru_RU translit misses: $misses")
        // The same words in the looser spellings people actually use: х as "h", щ as "sch", ц as
        // "c", й as "j", ё as plain "e" — the variations the scheme's folding exists for.
        val casual = mapOf('х' to "h", 'щ' to "sch", 'ц' to "c", 'й' to "j", 'ё' to "e", 'ы' to "i")
        var casualTop3 = 0; var casualTotal = 0
        for (sentence in sentences.take(400)) for (i in sentence.indices) {
            val word = sentence[i]
            if (word.none { it in casual }) continue
            val typed = word.map { casual[it] ?: scheme.romanize(it.toString()) }.joinToString("")
            casualTotal++
            val context = transliterator.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2))
            if (word in transliterator.candidates(typed, context, 3)) casualTop3++
        }
        val casualRate = 100.0 * casualTop3 / casualTotal
        println("ru_RU translit, casual spellings: top-3 %.1f %% of %d".format(casualRate, casualTotal))
        for ((latin, cyrillic) in listOf("privet" to "привет", "spasibo" to "спасибо", "horosho" to "хорошо",
                "kak dela" to "как", "pozhaluysta" to "пожалуйста", "segodnya" to "сегодня", "eto" to "это")) {
            assertTrue("$latin → $cyrillic", cyrillic in transliterator.candidates(latin.substringBefore(' '), limit = 3))
        }

        assertTrue("touch-noise correction regressed: $noise", noise.fixedRate >= RU_NOISE_FIXED && noise.wrongRate <= RU_NOISE_WRONG)
        assertTrue("phonetic touch-noise correction regressed: $phoneticNoise", phoneticNoise.fixedRate >= RU_NOISE_FIXED && phoneticNoise.wrongRate <= RU_NOISE_WRONG)
        assertTrue("damage to correct words regressed: $damage", damage <= RU_DAMAGE)
        assertTrue("transliteration regressed: top-1 $top1Rate top-3 $top3Rate", top1Rate >= RU_TRANSLIT_TOP1 && top3Rate >= RU_TRANSLIT_TOP3)
        assertTrue("casual-spelling transliteration regressed: $casualRate", casualRate >= RU_TRANSLIT_CASUAL_TOP3)
    }

    /** Percentage of correctly typed words of three letters or more that correction changes. */
    private fun damage(engine: Engine, sentences: List<List<String>>): Double {
        var clean = 0; var damaged = 0
        for (sentence in sentences) for (i in sentence.indices) {
            val word = sentence[i]
            if (word.length < 3) continue
            clean++
            val result = engine.index.correct(word, engine.index.contextOf(sentence.getOrNull(i - 1), sentence.getOrNull(i - 2)))
            if (result != null && result != word) damaged++
        }
        return 100.0 * damaged / clean
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

// Portuguese floors, set just under the measured values in the class doc.
private const val PT_ACCENT_FIXED = 91.0
private const val PT_ACCENT_WRONG = 4.0
private const val PT_OFFERED = 48.0
private const val PT_NOISE_FIXED = 61.0
private const val PT_NOISE_WRONG = 9.0
private const val PT_DAMAGE = 0.5

// Italian and Russian floors, set just under the measured values in the class doc.
private const val IT_ACCENT_FIXED = 93.0
private const val IT_ACCENT_WRONG = 6.5
private const val IT_NOISE_FIXED = 60.0
private const val IT_NOISE_WRONG = 9.0
private const val IT_DAMAGE = 0.3
private const val RU_NOISE_FIXED = 58.0
private const val RU_NOISE_WRONG = 9.5
private const val RU_DAMAGE = 0.5
private const val RU_TRANSLIT_TOP1 = 96.0
private const val RU_TRANSLIT_TOP3 = 99.0
private const val RU_TRANSLIT_CASUAL_TOP3 = 92.0
