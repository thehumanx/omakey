package dev.omakey.core.layout

/** [this] with [label] shown on the spacebar — the active language's name, so the user can see
 * which language they are typing in. Nothing else changes. */
fun KeyboardLayout.withSpaceLabel(label: String): KeyboardLayout = copy(
    rows = rows.map { row ->
        if (row.keys.none { it.code == SpecialKeyCode.SPACE }) return@map row
        row.copy(keys = row.keys.map { if (it.code == SpecialKeyCode.SPACE) it.copy(label = label) else it })
    },
)

/** [this] with the emoji key replaced, in place and at the same width, by a language key — for
 * when the user has moved the emoji button up to the suggestion bar and the language button down
 * here. A layout without an emoji key is returned unchanged. */
fun KeyboardLayout.withLanguageKeyInsteadOfEmoji(): KeyboardLayout = copy(
    rows = rows.map { row ->
        row.copy(
            keys = row.keys.map { key ->
                if (key.code != SpecialKeyCode.EXTENSIONS) {
                    key
                } else {
                    key.copy(label = "🌐", code = SpecialKeyCode.LANGUAGE, popupChars = emptyList())
                }
            },
        )
    },
)

/** The layout's own name if it has one; otherwise "AZERTY", "QWERTY" — named by its first six
 * letters, the way keyboards are. Shown wherever the user picks between a language's layouts. */
fun shortName(layout: KeyboardLayout): String =
    layout.displayName ?: layout.rows.firstOrNull()?.keys.orEmpty()
        .filter { it.keyType == KeyType.CHARACTER }
        .take(6)
        .joinToString("") { it.label }
        .uppercase()
