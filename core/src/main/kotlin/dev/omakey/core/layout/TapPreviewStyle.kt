package dev.omakey.core.layout

/**
 * How a tapped letter is shown while the finger is on it ([LayoutSettings.tapPreviewStyle]).
 * Users pick one in Settings; there is no single right answer — a bubble is easiest to read under a
 * thumb, while some find any popup heavy.
 */
enum class TapPreviewStyle {
    /** The enlarged-letter bubble omakey always had: wider than the key, solid. */
    CLASSIC,

    /** A key-wide, shorter, slightly see-through bubble. */
    SLIM,

    /** No bubble: the pressed key itself rises and grows a little. */
    LIFT,

    /** Only the pressed key's own highlight. */
    OFF,
    ;

    /** Draws a bubble above the key (as opposed to changing the key itself, or nothing). */
    val showsBubble: Boolean get() = this == CLASSIC || this == SLIM

    companion object {
        val DEFAULT = CLASSIC

        /**
         * The stored style, by enum name. Before this setting existed there was only an on/off
         * switch ([legacyShown]): a phone that had it off keeps it off, and an unknown name (a
         * newer build's style after a downgrade) falls back rather than throwing.
         */
        fun fromStored(name: String?, legacyShown: Boolean): TapPreviewStyle =
            entries.firstOrNull { it.name == name } ?: if (legacyShown) DEFAULT else OFF
    }
}
