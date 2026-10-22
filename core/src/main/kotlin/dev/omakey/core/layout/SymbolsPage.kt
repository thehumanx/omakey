package dev.omakey.core.layout

/**
 * This letter layout with each letter's long-press symbol moved from the symbols page it was
 * written against ([from]) to the one in use ([to]).
 *
 * Every letter layout — English in code, every language pack in JSON — gives each letter, as its
 * last long-press option, the symbol sitting in the same place on the first symbols page ("@" on
 * "a" because "@" is the first key of that page's home row). Switching to Fleksy's page without
 * this would leave "a" offering "@" while the key under "a" on the symbols page is "&". Matching by
 * position, rather than editing each layout, keeps every installed language right without a pack
 * update.
 *
 * Only letters change, and only their mirrored symbol: digits are the same on both pages, and the
 * "." key's punctuation list is a list of its own, not a mirror.
 */
fun KeyboardLayout.withSymbolsPage(from: KeyboardLayout, to: KeyboardLayout): KeyboardLayout {
    if (from === to) return this
    val moved = HashMap<String, String>()
    for (rowIndex in 1 until minOf(from.rows.size, to.rows.size)) {
        val fromKeys = from.rows[rowIndex].keys.filter { it.keyType == KeyType.CHARACTER }
        val toKeys = to.rows[rowIndex].keys.filter { it.keyType == KeyType.CHARACTER }
        // The bottom row's "." is the same key on both pages.
        if (fromKeys.size <= 1) continue
        for (index in fromKeys.indices) {
            val target = toKeys.getOrNull(index) ?: continue
            moved.putIfAbsent(fromKeys[index].committedText, target.committedText)
        }
    }
    if (moved.isEmpty()) return this
    var changed = false
    val remapped = rows.map { row ->
        KeyRow(
            row.keys.map { key ->
                val symbol = key.secondarySymbol
                val replacement = symbol?.let(moved::get)
                if (key.keyType != KeyType.CHARACTER || !key.label.first().isLetter() || replacement == null) {
                    key
                } else {
                    changed = true
                    key.copy(popupChars = key.popupChars.map { if (it == symbol) replacement else it })
                }
            },
        )
    }
    return if (changed) copy(rows = remapped) else this
}
