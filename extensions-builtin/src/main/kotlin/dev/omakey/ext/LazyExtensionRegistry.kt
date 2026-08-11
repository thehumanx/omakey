package dev.omakey.ext

import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionRegistry
import dev.omakey.extapi.OmakeyExtension

/**
 * Registers extension factories, not instances. An extension is only constructed (and only pays
 * its startup cost) the first time its panel is opened — keeps resident memory near zero for
 * users who never touch the extension panel.
 */
class LazyExtensionRegistry(private val contextProvider: () -> ExtensionContext) : ExtensionRegistry {

    private val factories = LinkedHashMap<String, () -> OmakeyExtension>()
    private val instances = HashMap<String, OmakeyExtension>()

    fun registerFactory(id: String, factory: () -> OmakeyExtension) {
        factories[id] = factory
    }

    override fun register(extension: OmakeyExtension) {
        factories[extension.id] = { extension }
    }

    override fun unregister(id: String) {
        instances.remove(id)?.onDetach()
        factories.remove(id)
    }

    override fun all(): List<OmakeyExtension> = factories.keys.mapNotNull { getById(it) }

    override fun getById(id: String): OmakeyExtension? {
        instances[id]?.let { return it }
        val factory = factories[id] ?: return null
        val instance = factory()
        instance.onAttach(contextProvider())
        instances[id] = instance
        return instance
    }
}
