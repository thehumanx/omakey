package dev.omakey.core.layout

/**
 * The split keyboard, done the way Gboard does it on foldables and tablets (9to5Google, Android
 * Police, Android Central): letter rows split down the middle, the middle letter of an odd row
 * (G and V on QWERTY) is repeated on both halves, and one long spacebar spans the gap.
 *
 * A split row is the same row with an invisible [KeyType.SPACER] gap key in the middle, so the
 * key grid, hit testing and gestures need nothing new: to them it is a row with a blank key.
 * Only docked keyboards split ([active]); a floating or one-handed one is already narrow.
 */
object SplitLayout {

    const val WIDE_SCREEN_DP = 600

    /** How much of each split row's width the gap takes. */
    const val GAP_FRACTION = 0.25f

    /** The gap key's code. Zero is "no key" to the hit tester, so a tap in the gap types nothing. */
    const val GAP_KEY_CODE = 0

    fun isWide(screenWidthDp: Int): Boolean = screenWidthDp >= WIDE_SCREEN_DP

    fun active(placement: KeyboardPlacement, screenWidthDp: Int, splitOnWide: Boolean, splitOnNarrow: Boolean): Boolean =
        placement == KeyboardPlacement.DOCKED && if (isWide(screenWidthDp)) splitOnWide else splitOnNarrow

    /**
     * The quick access "Split" tile. Docked: flips split for the screen class the keyboard is on,
     * so turning it off in landscape leaves an unfolded foldable alone, and the other way round.
     * Floating or one-handed: docks the keyboard and splits it, the way the placement tiles switch
     * between modes, rather than flipping a preference that would change nothing visible.
     */
    fun toggled(settings: LayoutSettings, screenWidthDp: Int): LayoutSettings {
        val wide = isWide(screenWidthDp)
        if (settings.placement != KeyboardPlacement.DOCKED) {
            val docked = settings.copy(placement = KeyboardPlacement.DOCKED)
            return if (wide) docked.copy(splitOnWide = true) else docked.copy(splitOnNarrow = true)
        }
        return if (wide) settings.copy(splitOnWide = !settings.splitOnWide) else settings.copy(splitOnNarrow = !settings.splitOnNarrow)
    }

    /** Number pads are small grids already and stay whole; letters and symbol pages split. */
    fun applies(layoutId: String): Boolean = layoutId !in NumberPads.IDS

    /** The rows to draw. The same list instance when nothing splits, so Compose can skip the grid. */
    fun rows(layoutId: String, rows: List<KeyRow>, split: Boolean, duplicateMiddle: Boolean): List<KeyRow> =
        if (!split || !applies(layoutId)) rows else rows.map { splitRow(it, duplicateMiddle) }

    /**
     * One row, split. The row with the spacebar is not cut: its spacebar grows by the gap's width
     * instead, so a single bar spans the middle. A row with no letters is returned as it is.
     *
     * Otherwise the row's letters (CHARACTER keys; special keys and existing spacer indents stay on
     * whichever side they are) are cut in the middle. An odd count repeats the middle letter on both
     * halves when [duplicateMiddle] is on, or puts it on the left when it is off. The lighter half is
     * then padded with blank space at its outer edge so both halves weigh the same, which keeps the
     * gap and the halves lined up from row to row without resizing any key.
     */
    fun splitRow(row: KeyRow, duplicateMiddle: Boolean): KeyRow {
        val keys = row.keys
        val ratio = GAP_FRACTION / (1f - GAP_FRACTION)
        val space = keys.indexOfFirst { it.code == SpecialKeyCode.SPACE }
        if (space >= 0) {
            val gap = keys.sumOf { it.widthWeight.toDouble() }.toFloat() * ratio
            return KeyRow(keys.mapIndexed { i, key -> if (i == space) key.copy(widthWeight = key.widthWeight + gap) else key })
        }
        val letters = keys.indices.filter { keys[it].keyType == KeyType.CHARACTER }
        if (letters.isEmpty()) return row
        val n = letters.size
        val (left, right) = if (n % 2 == 0) {
            val cut = letters[n / 2 - 1]
            keys.subList(0, cut + 1) to keys.subList(cut + 1, keys.size)
        } else {
            val middle = letters[n / 2]
            keys.subList(0, middle + 1) to keys.subList(if (duplicateMiddle) middle else middle + 1, keys.size)
        }
        val leftWeight = left.sumOf { it.widthWeight.toDouble() }.toFloat()
        val rightWeight = right.sumOf { it.widthWeight.toDouble() }.toFloat()
        val half = maxOf(leftWeight, rightWeight)
        // The lighter half is padded at its outer edge rather than having its keys widened, so
        // every letter keeps its size even on a lopsided row (shift with no backspace opposite).
        fun pad(weight: Float) = KeyDefinition("", GAP_KEY_CODE, widthWeight = weight, keyType = KeyType.SPACER)
        val paddedLeft = if (leftWeight < half) listOf(pad(half - leftWeight)) + left else left
        val paddedRight = if (rightWeight < half) right + pad(half - rightWeight) else right
        return KeyRow(paddedLeft + pad(2f * half * ratio) + paddedRight)
    }
}
