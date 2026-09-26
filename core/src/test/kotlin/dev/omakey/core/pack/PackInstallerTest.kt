package dev.omakey.core.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PackInstallerTest {

    @get:Rule val temp = TemporaryFolder()

    private val root: File get() = File(temp.root, "languages")

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected InvalidPackException")
        } catch (_: InvalidPackException) {
        }
    }

    @Test
    fun `a valid pack installs and loads as a language`() {
        val zip = TestPacks.zip(temp.newFile("es.zip"))
        val locale = PackInstaller(root).install(zip, PackInstaller.sha256(zip))
        assertEquals("es_ES", locale.id)
        assertEquals("Prueba", locale.nativeName)
        assertEquals("qwerty_test", locale.letterLayout.id)
        assertEquals(listOf("👋"), locale.profile.emojiFor("Hola"))
        assertTrue((locale.languageModel as dev.omakey.core.locale.ModelSource.File).path.startsWith(File(root, "es_ES").canonicalPath))
        assertEquals(listOf("es_ES"), PackInstaller(root).installed().map { it.id })
        assertEquals(mapOf("es_ES" to "1.0.0"), PackInstaller(root).installedVersions())
        assertFalse("no staging directory may be left behind", root.listFiles()!!.any { it.name.startsWith(".") })
    }

    @Test
    fun `a checksum mismatch is refused before anything is written`() {
        val zip = TestPacks.zip(temp.newFile("es.zip"))
        expectInvalid { PackInstaller(root).install(zip, "0".repeat(64)) }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an entry escaping the pack directory is refused`() {
        val entries = TestPacks.validEntries() + ("../../evil.txt" to "x".toByteArray())
        val zip = TestPacks.zip(temp.newFile("slip.zip"), entries)
        expectInvalid { PackInstaller(root).install(zip) }
        assertFalse(File(temp.root, "evil.txt").exists())
        assertFalse(File(root.parentFile, "evil.txt").exists())
    }

    @Test
    fun `a broken update leaves the installed version untouched`() {
        val installer = PackInstaller(root)
        installer.install(TestPacks.zip(temp.newFile("v1.zip")))
        val broken = TestPacks.validEntries(version = "2.0.0") + ("lm.bin" to "not a model".toByteArray())
        expectInvalid { installer.install(TestPacks.zip(temp.newFile("v2.zip"), broken)) }
        assertEquals(mapOf("es_ES" to "1.0.0"), installer.installedVersions())
        assertEquals(1, installer.installed().size)
    }

    @Test
    fun `a good update replaces the installed version`() {
        val installer = PackInstaller(root)
        installer.install(TestPacks.zip(temp.newFile("v1.zip")))
        installer.install(TestPacks.zip(temp.newFile("v2.zip"), TestPacks.validEntries(version = "1.1.0")))
        assertEquals(mapOf("es_ES" to "1.1.0"), installer.installedVersions())
    }

    @Test
    fun `manifest problems are refused`() {
        val installer = PackInstaller(root)
        val cases = listOf(
            TestPacks.validEntries() - "manifest.json",
            TestPacks.validEntries() + ("manifest.json" to "{ nope".toByteArray()),
            TestPacks.validEntries() + ("manifest.json" to TestPacks.manifest().replace("\"packFormat\":1", "\"packFormat\":99").toByteArray()),
            TestPacks.validEntries() + ("manifest.json" to TestPacks.manifest(id = "../x").toByteArray()),
            TestPacks.validEntries() + ("manifest.json" to TestPacks.manifest(layoutId = "missing").toByteArray()),
            TestPacks.validEntries() + ("profile.json" to """{"script":"KLINGON","hasCase":true}""".toByteArray()),
            TestPacks.validEntries() - "lm.bin",
        )
        cases.forEachIndexed { i, entries -> expectInvalid { installer.install(TestPacks.zip(temp.newFile("bad$i.zip"), entries)) } }
        assertTrue(installer.installed().isEmpty())
    }

    @Test
    fun `a pack needing a newer app is refused`() {
        val entries = TestPacks.validEntries() + ("manifest.json" to TestPacks.manifest().replace("\"packFormat\":1", "\"packFormat\":1,\"minAppVersionCode\":999").toByteArray())
        expectInvalid { PackInstaller(root, appVersionCode = 30).install(TestPacks.zip(temp.newFile("new.zip"), entries)) }
    }

    @Test
    fun `uninstall removes it and a corrupted install is skipped, not fatal`() {
        val installer = PackInstaller(root)
        installer.install(TestPacks.zip(temp.newFile("es.zip")))
        File(root, "fr_FR").mkdirs() // a directory with no manifest
        val failures = mutableMapOf<String, String>()
        assertEquals(listOf("es_ES"), installer.installed(failures).map { it.id })
        assertTrue("fr_FR" in failures)
        installer.uninstall("es_ES")
        assertTrue(installer.installed().isEmpty())
    }
}
