package dev.omakey.core.layout

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

/** Every Quick access tile, in its default order. Extras added later are off until turned on. */
enum class QuickTileId(val shownByDefault: Boolean = true) {
    INCOGNITO, ONE_HANDED, FLOATING, RESIZE, GRID, COMPACT, SPLIT, THEME, SETTINGS, LANGUAGE,
    AUTOCORRECT(false), CLIPBOARD(false), EMOJI(false),
}

/** Which tiles Quick access shows, in what order. At least one stays shown. */
data class QuickAccessTiles(val order: List<QuickTileId>, val hidden: Set<QuickTileId>) {
    val shown: List<QuickTileId> get() = order.filter { it !in hidden }

    fun moved(from: Int, to: Int): QuickAccessTiles = copy(order = dev.omakey.core.ui.Reorder.moved(order, from, to))

    fun withShown(tile: QuickTileId, show: Boolean): QuickAccessTiles {
        val next = if (show) hidden - tile else hidden + tile
        return if (order.all { it in next }) this else copy(hidden = next)
    }

    companion object {
        /** Stored order and hidden names → tiles. Unknown names are dropped; tiles the stored
         * order doesn't know (added in a later version) join at the end, hidden unless they are
         * shown by default. Nothing stored: the default order and defaults. */
        fun parse(storedOrder: String?, storedHidden: Set<String>?): QuickAccessTiles {
            val known = storedOrder.orEmpty().split(',').mapNotNull { name -> QuickTileId.entries.firstOrNull { it.name == name } }.distinct()
            val added = QuickTileId.entries - known.toSet()
            val hidden = storedHidden.orEmpty().mapNotNull { name -> QuickTileId.entries.firstOrNull { it.name == name } }.toSet() +
                added.filterNot { it.shownByDefault }
            return QuickAccessTiles(known + added, hidden)
        }
    }
}

/** Same SharedPreferences + StateFlow pattern as every other `*Preferences` class: Settings and
 * the keyboard each hold one, and the listener keeps an open keyboard in sync. */
class QuickAccessPreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val tiles: StateFlow<QuickAccessTiles> = store.settings

    fun move(from: Int, to: Int) = save(tiles.value.moved(from, to))

    fun setShown(tile: QuickTileId, show: Boolean) = save(tiles.value.withShown(tile, show))

    fun close() = store.close()

    private fun save(t: QuickAccessTiles) = store.edit {
        putString(KEY_ORDER, t.order.joinToString(",") { it.name })
        putStringSet(KEY_HIDDEN, t.hidden.map { it.name }.toSet())
    }

    private fun load(prefs: SharedPreferences) =
        QuickAccessTiles.parse(prefs.getString(KEY_ORDER, null), prefs.getStringSet(KEY_HIDDEN, null))

    internal companion object {
        const val PREFS_NAME = "omakey_quick_access_prefs"
        const val KEY_ORDER = "order"
        const val KEY_HIDDEN = "hidden"
    }
}
