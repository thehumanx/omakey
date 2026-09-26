package dev.omakey.core.pack

import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds pack zips for tests from the tiny multi-script model fixture. */
object TestPacks {

    private val json = Json { encodeDefaults = false }

    val model: File get() = File(javaClass.classLoader!!.getResource("lm/tiny_multiscript.bin")!!.toURI())

    fun manifest(id: String = "es_ES", version: String = "1.0.0", layoutId: String = "qwerty_test") = """
        {"id":"$id","displayName":"Test","nativeName":"Prueba","packVersion":"$version","packFormat":1,
         "letterLayout":"$layoutId","layouts":["layouts/$layoutId.json"]}
    """.trimIndent()

    fun layout(id: String = "qwerty_test"): String =
        json.encodeToString(KeyboardLayout.serializer(), Layouts.QwertyEnUS.copy(id = id))

    const val PROFILE = """{"script":"LATIN","hasCase":true,"equivalentLetters":["oó","nñ"],"emojiWords":{"hola":["👋"]}}"""

    /** A zip with [entries] (path → bytes); by default a complete, valid pack. */
    fun zip(
        into: File,
        entries: Map<String, ByteArray> = validEntries(),
    ): File {
        ZipOutputStream(into.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return into
    }

    fun validEntries(id: String = "es_ES", version: String = "1.0.0"): Map<String, ByteArray> = mapOf(
        "manifest.json" to manifest(id, version).toByteArray(),
        "layouts/qwerty_test.json" to layout().toByteArray(),
        "profile.json" to PROFILE.toByteArray(),
        "lm.bin" to model.readBytes(),
    )
}
