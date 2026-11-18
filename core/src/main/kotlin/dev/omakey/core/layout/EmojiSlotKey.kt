package dev.omakey.core.layout

/** The key left of the spacebar is the emoji key — no choice made. */
const val EMOJI_SLOT_DEFAULT = ""

/** The key left of the spacebar switches language instead. Never a single visible character, so it
 * can't collide with a typed choice. */
const val EMOJI_SLOT_LANGUAGE = "language"

/**
 * This layout with the emoji key (left of the spacebar) replaced, in place and at the same width,
 * by the user's choice ([LayoutSettings.emojiSlotKey]): the language key, or a key that types one
 * character. Same idea as [withSpacebarSideKey], applied at display time so every language follows
 * it without a pack update.
 *
 * Nothing becomes unreachable: whenever the emoji key is taken, the emoji button moves to the
 * suggestion bar ([emojiKeyInBar], read by [SuggestionBarButtons]). The language key needs a second
 * language; with one, the emoji key stays.
 */
fun KeyboardLayout.withEmojiSlotKey(choice: String, languageCount: Int): KeyboardLayout = when {
    choice == EMOJI_SLOT_DEFAULT -> this
    choice == EMOJI_SLOT_LANGUAGE -> if (languageCount >= 2) withLanguageKeyInsteadOfEmoji() else this
    keyForCode(SpecialKeyCode.EXTENSIONS) == null -> this
    else -> copy(
        rows = rows.map { row ->
            row.copy(
                keys = row.keys.map { key ->
                    if (key.code != SpecialKeyCode.EXTENSIONS) {
                        key
                    } else {
                        // Its own code, so it never clashes with a key that types the same
                        // character elsewhere (taps are identified by code; see KeyDefinition.text).
                        key.copy(
                            label = choice,
                            code = SpecialKeyCode.EMOJI_SLOT_CHARACTER,
                            text = choice,
                            keyType = KeyType.CHARACTER,
                            popupChars = emptyList(),
                        )
                    }
                },
            )
        },
    )
}

/** Whether the emoji button sits in the suggestion bar because its key was given to [choice]. */
fun emojiKeyInBar(choice: String, languageCount: Int): Boolean = when (choice) {
    EMOJI_SLOT_DEFAULT -> false
    EMOJI_SLOT_LANGUAGE -> languageCount >= 2
    else -> true
}

/** [value] if it is the language key or exactly one visible character, else the default. */
fun sanitizeEmojiSlotKey(value: String?): String =
    if (value == EMOJI_SLOT_LANGUAGE) value else sanitizeSpacebarSideKey(value).ifEmpty { EMOJI_SLOT_DEFAULT }

/** The stored choice; before it existed there was only "Swap emoji and language buttons". */
fun emojiSlotKeyFromStored(value: String?, legacySwap: Boolean): String =
    if (value == null) (if (legacySwap) EMOJI_SLOT_LANGUAGE else EMOJI_SLOT_DEFAULT) else sanitizeEmojiSlotKey(value)
