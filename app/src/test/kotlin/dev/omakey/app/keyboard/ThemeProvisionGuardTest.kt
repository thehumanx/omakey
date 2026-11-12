package dev.omakey.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the rule behind the "emoji panel is dark on a light phone" bug.
 *
 * The stored theme can be a sentinel (`Auto`, `Accent`) whose own colours are Dark's or a fixed
 * indigo. Only [resolveEffectiveTheme] turns it into what should be drawn. The service resolves it
 * once and provides it as `LocalOmakeyTheme`. The bug was `ExtensionPanelSlot` providing the
 * *stored* theme again, underneath, so every panel in that slot ignored the system setting.
 *
 * So: keyboard UI and extensions never read the stored theme, and only the service provides
 * `LocalOmakeyTheme`.
 */
class ThemeProvisionGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun kotlinFiles(relative: String): List<File> =
        File(repoRoot, relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val renderSources: List<File> by lazy {
        kotlinFiles("app/src/main/kotlin/dev/omakey/app/keyboard/ui") +
            kotlinFiles("extensions-builtin/src/main")
    }

    @Test fun `sources were found`() {
        assertTrue("no render sources found under $repoRoot", renderSources.size > 3)
    }

    @Test fun `keyboard UI never reads the stored theme`() {
        val offenders = renderSources.filter { it.readText().contains("storedTheme") }.map { it.name }
        assertEquals("render code must use LocalOmakeyTheme, not uiState.storedTheme", emptyList<String>(), offenders)
    }

    @Test fun `only the service provides LocalOmakeyTheme`() {
        val providers = (kotlinFiles("app/src/main") + kotlinFiles("extensions-builtin/src/main"))
            .filter { Regex("""LocalOmakeyTheme\s+provides""").containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals(listOf("OmakeyInputMethodService.kt"), providers)
    }
}
