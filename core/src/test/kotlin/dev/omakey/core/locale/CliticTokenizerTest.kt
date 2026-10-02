package dev.omakey.core.locale

import dev.omakey.core.pack.ProfileSpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The keyboard must split elided words exactly the way the model was built, or "l'" is never
 * context for "homme" and every elided word is scored as unknown. The builder (`build_lang_lm.py`)
 * writes its own tokenisation of a set of cases to `eval/<id>/tokenizer_cases.txt`; this holds the
 * runtime split, using the pack's real `profile.json`, to the same answers — for every language
 * with clitics (French, Italian).
 */
class CliticTokenizerTest {

    private fun profile(id: String): LanguageProfile {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "languages/$id/profile.json").isFile) dir = dir.parentFile
        val text = File(dir ?: error("languages/$id not found"), "languages/$id/profile.json").readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(ProfileSpec.serializer(), text).toProfile()
    }

    private val profile: LanguageProfile by lazy { profile("fr_FR") }

    private fun runtimeTokens(profile: LanguageProfile, word: String): List<String> =
        profile.splitClitic(word)?.let { (clitic, rest) -> listOf(clitic.toLookupForm(), rest.toLookupForm()) }
            ?: listOf(word.toLookupForm())

    private fun assertMatchesBuilder(id: String) {
        val profile = profile(id)
        val cases = File(javaClass.classLoader!!.getResource("eval/$id/tokenizer_cases.txt")!!.toURI())
            .readLines().filter { it.isNotBlank() }
        assertTrue(cases.size >= 10)
        for (line in cases) {
            val (raw, tokens) = line.split('\t')
            assertEquals("$id: $raw", tokens.split(' '), runtimeTokens(profile, raw))
        }
    }

    @Test
    fun `runtime clitic split matches the model builder`() = assertMatchesBuilder("fr_FR")

    @Test
    fun `Italian runtime clitic split matches the model builder`() = assertMatchesBuilder("it_IT")

    @Test
    fun `the typed case of both halves is kept`() {
        assertEquals("L'" to "Homme", profile.splitClitic("L'Homme"))
        assertEquals("l’" to "amour", profile.splitClitic("l’amour"))
    }
}
