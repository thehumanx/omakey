package dev.omakey.core.layout

private fun charKey(label: String, popup: List<String> = emptyList()) =
    KeyDefinition(label = label, code = label.first().code, popupChars = popup)

/** Bundled layouts. Kotlin objects for v1; data classes are @Serializable so a future JSON-loading
 * path (community/alternate layouts) is additive, not a restructure. */
object Layouts {

    /** Rendered in the top strip's "numbers" tab, not as a persistent grid row — reused here
     * rather than duplicated since it's the same 1-0 key set as the Symbols layout's first row. */
    val NumberRow = KeyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map(::charKey))

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
                    *listOf("*", "\"", "'", ":", ";", "!", "?").map(::charKey).toTypedArray(),
                    KeyDefinition("⌫", SpecialKeyCode.BACKSPACE, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("ABC", SpecialKeyCode.LETTERS, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    KeyDefinition(" ", SpecialKeyCode.SPACE, widthWeight = 4f, keyType = KeyType.SPECIAL),
                    charKey(".", listOf(",", "!", "?", "-", ":", ";", "'")),
                    KeyDefinition("⏎", SpecialKeyCode.ENTER, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
        ),
    )
}
