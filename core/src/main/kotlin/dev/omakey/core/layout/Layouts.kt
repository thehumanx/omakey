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

    // Each letter's `popupChars` ends with the symbol sitting at the *same grid position* on
    // `Symbols1` (row 1 <-> digits, row 2 <-> `@#$_&-+()`, row 3 <-> `*"':,;!`) — long-pressing
    // "z" (row 3, position 1) pops up "*" (Symbols1 row 3, position 1, right after the row's
    // `=\<` toggle key, which isn't a real symbol) for exactly that reason. Vowels keep their
    // existing accent variants first, with the mirrored symbol appended after them rather than
    // replacing them. Symbols1 row 2 has one more key (10) than this row has letters (9), and row
    // 3 has one more (8, after excluding `=\<`) than this row has letters (7) — `/` and `?` are
    // simply left unmirrored rather than double-mapped onto an existing key.
    val QwertyEnUS = KeyboardLayout(
        id = "qwerty_en_us",
        rows = listOf(
            KeyRow(
                listOf(
                    charKey("q", listOf("1")), charKey("w", listOf("2")), charKey("e", listOf("è", "é", "ê", "ë", "3")),
                    charKey("r", listOf("4")), charKey("t", listOf("5")), charKey("y", listOf("6")), charKey("u", listOf("ù", "ú", "û", "ü", "7")),
                    charKey("i", listOf("ì", "í", "î", "ï", "8")), charKey("o", listOf("ò", "ó", "ô", "õ", "ö", "9")),
                    charKey("p", listOf("0")),
                ),
            ),
            KeyRow(
                listOf(
                    charKey("a", listOf("à", "á", "â", "ä", "@")), charKey("s", listOf("#")), charKey("d", listOf("$")), charKey("f", listOf("_")),
                    charKey("g", listOf("&")), charKey("h", listOf("-")), charKey("j", listOf("+")), charKey("k", listOf("(")), charKey("l", listOf(")")),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("⇧", SpecialKeyCode.SHIFT, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    charKey("z", listOf("*")), charKey("x", listOf("\"")), charKey("c", listOf("'")), charKey("v", listOf(":")), charKey("b", listOf(",")),
                    charKey("n", listOf(";")), charKey("m", listOf("!")),
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
