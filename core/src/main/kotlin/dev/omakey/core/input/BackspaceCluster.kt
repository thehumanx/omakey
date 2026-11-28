package dev.omakey.core.input

/**
 * How many UTF-16 units one backspace removes from the end of [before]: one visible emoji, whatever
 * it is made of, or else one code point.
 *
 * An emoji is often several code points: a skin tone (👍🏽), a variation selector (❤️), a keycap
 * (1️⃣), people joined by zero-width joiners (👨‍👩‍👧, 👩🏽‍💻), a pair of regional indicators (🇳🇵)
 * or a tag sequence (the Scotland flag). One backspace per code point took up to seven taps for
 * one emoji and showed broken pieces on the way.
 *
 * Deliberately not full grapheme clustering: combining marks stay one code point per backspace, so
 * Devanagari and other scripts that build a letter from parts can still remove one part at a time,
 * as they always have here. `java.text.BreakIterator` is not used because on the JVM (unit tests)
 * it doesn't know emoji sequences, and `android.icu` isn't there at all.
 *
 * [before] should hold enough text to see a whole cluster (64 units covers every emoji in use);
 * a sequence cut off at its start deletes what is visible, which is never less than before.
 */
object BackspaceCluster {
    fun length(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        val end = before.length
        var start = previous(before, end)
        val last = Character.codePointAt(before, start)

        if (isRegionalIndicator(last)) {
            // Flags are pairs; count the run of indicators to know which half this one is.
            var run = 1
            var i = start
            while (i > 0) {
                val p = previous(before, i)
                if (!isRegionalIndicator(Character.codePointAt(before, p))) break
                run++
                i = p
            }
            return if (run % 2 == 0) end - previous(before, start) else end - start
        }

        while (start > 0) {
            val cp = Character.codePointAt(before, start)
            if (isExtender(cp)) {
                // A modifier belongs to whatever comes before it.
                start = previous(before, start)
                continue
            }
            val p = previous(before, start)
            if (Character.codePointAt(before, p) == ZWJ && p > 0) {
                // Joined to the emoji before the joiner.
                start = previous(before, p)
                continue
            }
            break
        }
        return end - start
    }

    private fun previous(s: CharSequence, index: Int): Int = index - Character.charCount(Character.codePointBefore(s, index))

    private const val ZWJ = 0x200D

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isExtender(cp: Int) =
        cp == 0xFE0F || cp == 0xFE0E || // variation selectors
            cp in 0x1F3FB..0x1F3FF || // skin tones
            cp == 0x20E3 || // combining keycap
            cp in 0xE0020..0xE007F // tag characters (subdivision flags)
}
