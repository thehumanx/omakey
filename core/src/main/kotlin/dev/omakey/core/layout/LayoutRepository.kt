package dev.omakey.core.layout

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Every keyboard layout the app knows, by id: the bundled Kotlin ones, plus (from AGENTS.md §66
 * Phase 7) those a language pack brings as JSON.
 *
 * A layout is only admitted if [KeyboardLayout.validate] passes and its [KeyboardLayout.shiftLayoutId]
 * resolves, so everything past this class can assume a layout is well formed.
 */
class LayoutRepository(bundled: List<KeyboardLayout> = Layouts.all) {

    private val byId = LinkedHashMap<String, KeyboardLayout>()

    init {
        bundled.forEach(::register)
    }

    operator fun get(id: String): KeyboardLayout? = byId[id]

    /** The layout Shift shows for [layout], or null when Shift changes case instead. */
    fun shiftLayerOf(layout: KeyboardLayout): KeyboardLayout? = layout.shiftLayoutId?.let(byId::get)

    /** Adds [layouts] together, so a base layout and its shift layer can reference each other.
     * Nothing is added if any of them is invalid. */
    fun registerAll(layouts: List<KeyboardLayout>) {
        val ids = layouts.map { it.id }.toSet()
        for (layout in layouts) {
            val problems = layout.validate().toMutableList()
            val shift = layout.shiftLayoutId
            if (shift != null && shift !in ids && shift !in byId) problems += "shift layer '$shift' not found"
            require(problems.isEmpty()) { "Invalid layout '${layout.id}': ${problems.joinToString("; ")}" }
        }
        layouts.forEach { byId[it.id] = it }
    }

    fun register(layout: KeyboardLayout) = registerAll(listOf(layout))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses one layout from JSON (the `KeyboardLayout` serial form). Throws
         * [IllegalArgumentException] for malformed JSON as well as for an invalid layout, so a
         * caller loading untrusted data has one exception to handle. */
        fun parse(text: String): KeyboardLayout {
            val layout = try {
                json.decodeFromString(KeyboardLayout.serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("Malformed layout JSON: ${e.message}", e)
            }
            val problems = layout.validate()
            require(problems.isEmpty()) { "Invalid layout '${layout.id}': ${problems.joinToString("; ")}" }
            return layout
        }
    }
}
