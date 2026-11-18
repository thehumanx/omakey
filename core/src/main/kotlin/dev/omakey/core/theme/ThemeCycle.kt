package dev.omakey.core.theme

/**
 * The theme after [currentId] for the quick-settings "Theme" tile: the built-in [presets] in order,
 * then the user's [customs] in the order Settings lists them, then back to the first preset.
 *
 * Custom themes used to be left out, so a user with their own theme could never reach it from the
 * tile (closed-beta request). An id in neither list — a custom theme deleted since it was picked —
 * starts the cycle again at the first preset.
 */
fun nextTheme(currentId: String, presets: List<OmakeyTheme>, customs: List<OmakeyTheme>): OmakeyTheme {
    val all = presets + customs
    val index = all.indexOfFirst { it.id == currentId }
    return if (index < 0) all.first() else all[(index + 1) % all.size]
}
