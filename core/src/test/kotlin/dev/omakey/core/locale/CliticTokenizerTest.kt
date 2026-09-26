package dev.omakey.core.locale

import dev.omakey.core.pack.ProfileSpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The keyboard must split elided French words exactly the way the model was built, or "l'" is
 * never context for "homme" and every elided word is scored as unknown. The builder
 * (`build_lang_lm.py`) writes its own tokenisation of a set of cases to
 * `eval/fr_FR/tokenizer_cases.txt`; this holds the runtime split, using the pack's real
 * `profile.json`, to the same answers.
 */
class CliticTokenizerTest {

    private val profile: LanguageProfile by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "languages/fr_FR/profile.json").isFile) dir = dir.parentFile
        val text = File(dir ?: error("languages/fr_FR not found"), "languages/fr_FR/profile.json").readText()
        Json { ignoreUnknownKeys = true }.decodeFromString(ProfileSpec.serializer(), text).toProfile()
    }

    private fun runtimeTokens(word: String): List<String> =
        profile.splitClitic(word)?.let { (clitic, rest) -> listOf(clitic.toLookupForm(), rest.toLookupForm()) }
            ?: listOf(word.toLookupForm())

    @Test
    fun `runtime clitic split matches the model builder`() {
        val cases = File(javaClass.classLoader!!.getResource("eval/fr_FR/tokenizer_cases.txt")!!.toURI())
            .readLines().filter { it.isNotBlank() }
        assertTrue(cases.size >= 10)
        for (line in cases) {
            val (raw, tokens) = line.split('\t')
            assertEquals(raw, tokens.split(' '), runtimeTokens(raw))
        }
    }

    @Test
    fun `the typed case of both halves is kept`() {
        assertEquals("L'" to "Homme", profile.splitClitic("L'Homme"))
        assertEquals("l’" to "amour", profile.splitClitic("l’amour"))
    }
}
