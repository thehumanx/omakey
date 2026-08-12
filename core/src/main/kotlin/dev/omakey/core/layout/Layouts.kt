package dev.omakey.core.layout

private fun charKey(label: String, popup: List<String> = emptyList(), widthWeight: Float = 1f) =
    KeyDefinition(label = label, code = label.first().code, popupChars = popup, widthWeight = widthWeight)

/** Bundled layouts. Kotlin objects for v1; data classes are @Serializable so a future JSON-loading
 * path (community/alternate layouts) is additive, not a restructure. */
object Layouts {

    /** Rendered in the top strip's "numbers" tab, not as a persistent grid row — reused here
     * rather than duplicated since it's the same 1-0 key set as the Symbols layout's first row. */
    val NumberRow = KeyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map(::charKey))

    /** Shown in that same top-strip "numbers" tab slot instead of [NumberRow] while a Symbols
     * layout is active — digits are already the Symbols grid's own first row, so repeating them
     * in the tab too is redundant; extra special characters that don't fit the main Symbols1/2
     * grids are more useful there. Same 10-key shape as [NumberRow] for identical sizing. */
    val SymbolsExtraRow = KeyRow(listOf("~", "%", "=", "{", "}", "<", ">", "]", "€", "£").map(::charKey))

    val QwertyEnUS = KeyboardLayout(
        id = "qwerty_en_us",
        rows = listOf(
            KeyRow(
                listOf(
                    charKey("q"), charKey("w"), charKey("e", listOf("è", "é", "ê", "ë")),
                    charKey("r"), charKey("t"), charKey("y"), charKey("u", listOf("ù", "ú", "û", "ü")),
                    charKey("i", listOf("ì", "í", "î", "ï")), charKey("o", listOf("ò", "ó", "ô", "õ", "ö")),
                    charKey("p"),
                ),
            ),
            KeyRow(
                listOf(
                    charKey("a", listOf("à", "á", "â", "ä")), charKey("s"), charKey("d"), charKey("f"),
                    charKey("g"), charKey("h"), charKey("j"), charKey("k"), charKey("l"),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("⇧", SpecialKeyCode.SHIFT, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    charKey("z"), charKey("x"), charKey("c"), charKey("v"), charKey("b"),
                    charKey("n"), charKey("m"),
                    KeyDefinition("⌫", SpecialKeyCode.BACKSPACE, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("?123", SpecialKeyCode.SYMBOLS, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    KeyDefinition("😊", SpecialKeyCode.EXTENSIONS, widthWeight = 1f, keyType = KeyType.SPECIAL),
                    KeyDefinition(" ", SpecialKeyCode.SPACE, widthWeight = 4f, keyType = KeyType.SPECIAL),
                    charKey(".", listOf(",", "!", "?", "-", ":", ";", "'")),
                    KeyDefinition("⏎", SpecialKeyCode.ENTER, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
        ),
    )

    val Symbols1 = KeyboardLayout(
        id = "symbols_1",
        rows = listOf(
            KeyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map(::charKey)),
            KeyRow(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map(::charKey)),
            KeyRow(
                listOf(
                    KeyDefinition("=\\<", SpecialKeyCode.SYMBOLS, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    // widthWeight 0.875 each (not the default 1f) so these 8 keys sum to the same
                    // 7f as QwertyEnUS's 7 letters in this row — otherwise this row's total weight
                    // (11 vs QwertyEnUS's 10) visibly shifts the backspace key's x-position when
                    // switching between the letters and symbols layouts.
                    *listOf("*", "\"", "'", ":", ",", ";", "!", "?").map { charKey(it, widthWeight = 0.875f) }.toTypedArray(),
                    KeyDefinition("⌫", SpecialKeyCode.BACKSPACE, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("ABC", SpecialKeyCode.LETTERS, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    // Fills the slot QwertyEnUS's emoji-launcher key occupies — Symbols pages have
                    // no emoji shortcut of their own, and leaving this slot out entirely was one
                    // widthWeight short of QwertyEnUS's total, visibly shifting every other key's
                    // width when switching between letters and symbols.
                    KeyDefinition("⚙", SpecialKeyCode.SETTINGS, widthWeight = 1f, keyType = KeyType.SPECIAL),
                    KeyDefinition(" ", SpecialKeyCode.SPACE, widthWeight = 4f, keyType = KeyType.SPECIAL),
                    charKey(".", listOf(",", "!", "?", "-", ":", ";", "'")),
                    KeyDefinition("⏎", SpecialKeyCode.ENTER, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
        ),
    )

    /** Second symbols page, reached via the "=\<" toggle key on [Symbols1] (and back again via
     * this page's "123" key) — the classic Gboard-style extra tier of less-common special
     * characters that don't fit the primary symbols grid. */
    val Symbols2 = KeyboardLayout(
        id = "symbols_2",
        rows = listOf(
            KeyRow(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map(::charKey)),
            KeyRow(listOf("£", "¥", "€", "¢", "^", "°", "=", "{", "}", "\\").map(::charKey)),
            KeyRow(
                listOf(
                    KeyDefinition("123", SpecialKeyCode.SYMBOLS, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    // Same widthWeight-parity fix as Symbols1's row 3 — see comment there.
                    *listOf("©", "®", "™", "✓", "[", "]", "<", ">").map { charKey(it, widthWeight = 0.875f) }.toTypedArray(),
                    KeyDefinition("⌫", SpecialKeyCode.BACKSPACE, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("ABC", SpecialKeyCode.LETTERS, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    KeyDefinition("⚙", SpecialKeyCode.SETTINGS, widthWeight = 1f, keyType = KeyType.SPECIAL),
                    KeyDefinition(" ", SpecialKeyCode.SPACE, widthWeight = 4f, keyType = KeyType.SPECIAL),
                    charKey(".", listOf(",", "!", "?", "-", ":", ";", "'")),
                    KeyDefinition("⏎", SpecialKeyCode.ENTER, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
        ),
    )
}
