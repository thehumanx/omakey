package dev.omakey.core.predict.eval

import dev.omakey.core.predict.lm.LanguageModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads the shipping language model asset in a plain JVM unit test, where there is no `Context` to
 * open assets with. Maps the file straight off disk — the same `ByteBuffer` path
 * [LanguageModel.from] takes on device, so tests exercise the real reader rather than a stand-in.
 *
 * Cached: the asset is several megabytes and a dozen test classes want it.
 */
object TestLanguageModel {

    private val cached: LanguageModel by lazy { LanguageModel.from(map(locate())) }

    fun load(): LanguageModel = cached

    private fun map(file: File): ByteBuffer =
        FileChannel.open(file.toPath()).use { it.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }

    private fun locate(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "core/src/main/assets/lm_en_us.bin")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error(
            "core/src/main/assets/lm_en_us.bin not found — generate it with `python3 scripts/build_lm.py`",
        )
    }
}
