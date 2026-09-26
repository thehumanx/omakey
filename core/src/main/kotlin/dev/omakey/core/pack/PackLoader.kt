package dev.omakey.core.pack

import dev.omakey.core.layout.LayoutRepository
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.locale.ModelSource
import dev.omakey.core.predict.lm.LanguageModel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Turns an unpacked language pack directory into a [KeyboardLocale], checking everything a
 * keyboard would otherwise discover the hard way: the manifest parses and names a format this app
 * reads, every layout is valid and the letter layout exists, the profile is well formed, and the
 * model file maps and reads as a model.
 *
 * Every failure is an [InvalidPackException] — one exception type for callers loading data that
 * arrived over the network.
 */
object PackLoader {

    private val json = Json { ignoreUnknownKeys = true }

    fun readManifest(dir: File): PackManifest = parse(File(dir, MANIFEST), PackManifest.serializer())

    fun load(dir: File, appVersionCode: Int = Int.MAX_VALUE): KeyboardLocale {
        val manifest = readManifest(dir)
        check(manifest.packFormat == PackManifest.FORMAT) {
            "pack format ${manifest.packFormat}; this app reads ${PackManifest.FORMAT}"
        }
        check(appVersionCode >= manifest.minAppVersionCode) {
            "needs app version ${manifest.minAppVersionCode} or newer"
        }
        check(manifest.id.matches(ID_PATTERN)) { "bad id '${manifest.id}'" }

        val layouts = manifest.layouts.map { path ->
            try {
                LayoutRepository.parse(inside(dir, path).readText())
            } catch (e: IllegalArgumentException) {
                throw InvalidPackException("layout $path: ${e.message}", e)
            }
        }
        val letterLayout = layouts.firstOrNull { it.id == manifest.letterLayout }
            ?: throw InvalidPackException("letter layout '${manifest.letterLayout}' is not among its layouts")
        val choices = manifest.letterLayoutChoices.map { id ->
            layouts.firstOrNull { it.id == id } ?: throw InvalidPackException("layout choice '$id' is not among its layouts")
        }
        if (choices.isNotEmpty() && letterLayout !in choices) throw InvalidPackException("letter layout is not one of its choices")
        // Shift layers must resolve within the pack; register into a scratch repository to check.
        try {
            LayoutRepository(bundled = emptyList()).registerAll(layouts)
        } catch (e: IllegalArgumentException) {
            throw InvalidPackException(e.message ?: "invalid layouts", e)
        }

        val profile = try {
            parse(inside(dir, manifest.profile), ProfileSpec.serializer()).toProfile()
        } catch (e: IllegalArgumentException) {
            throw InvalidPackException("profile: ${e.message}", e)
        }

        val modelFile = inside(dir, manifest.model)
        try {
            LanguageModel.load(modelFile)
        } catch (e: Exception) {
            throw InvalidPackException("model: ${e.message}", e)
        }

        return KeyboardLocale(
            id = manifest.id,
            displayName = manifest.displayName,
            nativeName = manifest.nativeName,
            shortLabel = manifest.shortLabel?.take(MAX_SHORT_LABEL),
            letterLayout = letterLayout,
            extraLayouts = layouts - letterLayout,
            letterLayoutChoices = choices,
            languageModel = ModelSource.File(modelFile.absolutePath),
            profile = profile,
        )
    }

    private fun <T> parse(file: File, serializer: kotlinx.serialization.KSerializer<T>): T {
        if (!file.isFile) throw InvalidPackException("missing ${file.name}")
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: SerializationException) {
            throw InvalidPackException("${file.name}: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidPackException("${file.name}: ${e.message}", e)
        }
    }

    /** [path] resolved inside [dir], refusing anything that would escape it. */
    internal fun inside(dir: File, path: String): File {
        val root = dir.canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) throw InvalidPackException("path escapes the pack: $path")
        if (!file.isFile) throw InvalidPackException("missing $path")
        return file
    }

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw InvalidPackException(message())
    }

    const val MANIFEST = "manifest.json"
    private val ID_PATTERN = Regex("[a-z]{2,3}_[A-Z]{2}")
}

class InvalidPackException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A spacebar has room for a few characters; anything longer in a manifest is cut rather than trusted. */
private const val MAX_SHORT_LABEL = 8
