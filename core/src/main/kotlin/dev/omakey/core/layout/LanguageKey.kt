package dev.omakey.core.layout

/** [this] with a language key inserted before the spacebar, paid for out of the spacebar's width
 * so every other key keeps its size, and [spaceLabel] shown on the spacebar. */
fun KeyboardLayout.withLanguageKey(spaceLabel: String): KeyboardLayout = copy(
    rows = rows.map { row ->
        val spaceIndex = row.keys.indexOfFirst { it.code == SpecialKeyCode.SPACE }
        if (spaceIndex < 0 || row.keys.any { it.code == SpecialKeyCode.LANGUAGE }) return@map row
        val space = row.keys[spaceIndex]
        val languageKey = KeyDefinition(
            label = "🌐",
            code = SpecialKeyCode.LANGUAGE,
            widthWeight = 1f,
            keyType = KeyType.SPECIAL,
        )
        val narrowerSpace = space.copy(label = spaceLabel, widthWeight = (space.widthWeight - 1f).coerceAtLeast(2f))
        row.copy(keys = row.keys.take(spaceIndex) + languageKey + narrowerSpace + row.keys.drop(spaceIndex + 1))
    },
)
