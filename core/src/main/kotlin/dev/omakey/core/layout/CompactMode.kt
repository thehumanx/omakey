package dev.omakey.core.layout

/**
 * Compact mode ([LayoutSettings.compactMode], the quick access "Compact" tile): the letter layout's
 * bottom row (spacebar, enter, symbols, emoji/language and the key beside the spacebar) is not
 * drawn, so the keyboard is one row shorter. Nothing replaces those keys; that is the choice the
 * user made. Swipe right types the space instead, whatever "Swipe right for space" says.
 *
 * Only letter layouts shrink. A number pad keeps its enter and delete, and a symbols page keeps
 * its "ABC" key, so turning compact on while one is showing never strands the user there.
 */
object CompactMode {

    private val SYMBOLS_IDS = setOf(Layouts.Symbols1.id, Layouts.Symbols2.id)

    /**
     * Whether compact is in effect right now, before the per-layout rule in [applies].
     * - Accessible mode (TalkBack, or forced) has no swipes, so hiding the spacebar there would
     *   leave no way to type a space.
     * - Quick access and the language picker ([tilePanelOpen]) open at full height. Their panel is as
     *   tall as the key grid, and one row shorter pushes tiles, the Compact tile that turns this
     *   off and the Language tile among them, out of view.
     */
    fun active(compact: Boolean, accessibleMode: Boolean, tilePanelOpen: Boolean): Boolean =
        compact && !accessibleMode && !tilePanelOpen

    fun applies(compact: Boolean, layoutId: String): Boolean =
        compact && layoutId !in NumberPads.IDS && layoutId !in SYMBOLS_IDS

    /** The rows to draw. The same list instance when nothing is dropped, so Compose can skip the
     * key grid. A one-row layout is left alone rather than drawn empty. */
    fun rows(layout: KeyboardLayout, compact: Boolean): List<KeyRow> =
        if (applies(compact, layout.id) && layout.rows.size > 1) layout.rows.dropLast(1) else layout.rows

    fun swipeRightTypesSpace(setting: Boolean, compactApplies: Boolean): Boolean = setting || compactApplies
}
