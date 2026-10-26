package dev.omakey.core.input

/**
 * Rules for a password manager's inline autofill chips (Android 11+): when the keyboard asks for
 * them, what order they show in, and how a response's chips — inflated one by one, asynchronously —
 * become one list without a slow older response overwriting a newer one.
 *
 * The chips themselves are views the autofill service draws; nothing here (or anywhere in omakey)
 * reads what they say.
 */
object InlineAutofill {
    /** Android 11, where `onCreateInlineSuggestionsRequest` first exists. */
    const val MIN_SDK = 30

    /**
     * Whether to ask Android for chips at all. Not asking is what lets the password manager fall back
     * to its own popup, so it is the right answer whenever the chips would have nowhere to show:
     * the setting off, or the Suggestions page (their only home) hidden.
     */
    fun shouldRequest(sdkInt: Int, enabled: Boolean, suggestionsPageShown: Boolean): Boolean =
        sdkInt >= MIN_SDK && enabled && suggestionsPageShown

    /**
     * The widest a chip may be, in px: what the strip has left for chips ([stripWidth] less
     * [reserved] — the buttons either side and the row's padding), less a [trailing] slot and the
     * [gap] before it. The slot is the row's "next chips" button, as wide as the manager's pinned
     * icon chip or wider, so the widest chip still fits on a page beside it ([chipPages]). The
     * autofill service draws the chip and cuts its text short to fit; omakey never touches the
     * text. Never below [minWidth], the spec's own minimum.
     */
    fun maxChipWidth(stripWidth: Int, reserved: Int, trailing: Int, gap: Int, minWidth: Int): Int =
        (stripWidth - reserved - trailing - gap).coerceAtLeast(minWidth)

    /**
     * Splits chips of [widths] (px) into pages that each fit [available] px whole, [gap] apart,
     * in order. Only whole chips are ever shown: a chip is the autofill service's own surface,
     * drawn above the keyboard where omakey can't clip it, so a chip half outside the row would
     * cover the buttons beside it (phone tests, four builds of trying to crop it).
     *
     * When everything fits there is one page. Otherwise each page also leaves room for the
     * [pagerButton] that moves between them, and its gap. A chip too wide even for that sits on a
     * page of its own; [maxChipWidth] is what keeps that from happening.
     */
    fun chipPages(widths: List<Int>, available: Int, gap: Int, pagerButton: Int): List<IntRange> {
        if (widths.isEmpty()) return emptyList()
        val all = pack(widths, available, gap)
        return if (all.size == 1) all else pack(widths, available - gap - pagerButton, gap)
    }

    private fun pack(widths: List<Int>, available: Int, gap: Int): List<IntRange> {
        val pages = mutableListOf<IntRange>()
        var start = 0
        while (start < widths.size) {
            var end = start
            var used = widths[start]
            while (end + 1 < widths.size && used + gap + widths[end + 1] <= available) {
                end++
                used += gap + widths[end]
            }
            pages += start..end
            start = end + 1
        }
        return pages
    }

    /** Pinned suggestions — the manager's own icon, which opens it — go last, after the logins,
     * the way the framework intends them (always visible at the end of the row). Order is kept
     * otherwise. */
    fun <T> pinnedLast(items: List<T>, isPinned: (T) -> Boolean): List<T> {
        val (pinned, plain) = items.partition(isPinned)
        return plain + pinned
    }
}

/**
 * Gathers one response's chips as they finish inflating, in the response's order, and [publish]es
 * the whole list once every one has arrived (a chip that fails to inflate arrives as null and is
 * left out). Starting a new response or [clear]ing makes every earlier batch stale, so a late
 * callback from a previous field can never put its chips back.
 *
 * Not thread-safe: the framework delivers responses and inflation callbacks on the main thread,
 * and this is only ever used there.
 */
class InlineChipCollector<T>(private val publish: (List<T>) -> Unit) {
    private var generation = 0

    /** A batch expecting [count] chips. An empty response publishes the empty list at once. */
    fun start(count: Int): Batch {
        generation++
        val batch = Batch(generation, count)
        if (count == 0) publish(emptyList())
        return batch
    }

    /** No chips, and nothing still inflating may add any. */
    fun clear() {
        generation++
        publish(emptyList())
    }

    inner class Batch internal constructor(private val id: Int, private val count: Int) {
        private val results = arrayOfNulls<Any?>(count)
        private var arrived = 0

        fun deliver(index: Int, item: T?) {
            if (id != generation || index !in 0 until count) return
            results[index] = item
            arrived++
            @Suppress("UNCHECKED_CAST")
            if (arrived == count) publish(results.filterNotNull() as List<T>)
        }
    }
}
