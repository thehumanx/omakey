package dev.omakey.core.layout

// Synthetic key-identity codes for this file's keys — negative, and deliberately outside both the
// range of real Unicode codepoints (always >= 0) and SpecialKeyCode's own reserved range (-1..-8)
// — so they're safe, collision-free KeyDefinition.code hit-test values. Never meant to be passed
// to Character.toChars: every key here sets [KeyDefinition.text] explicitly (even for single-
// codepoint output), so nothing ever needs to fall back to interpreting `code` as a codepoint.
// KeyboardViewModel.onCharacter still guards Character.isValidCodePoint before that fallback path
// as a defensive no-op, in case some future call site ever reaches it for one of these keys.
private var nextSyntheticCode = -2000
private fun syntheticCode(): Int = nextSyntheticCode--

/** A Devanagari key: [unshifted] is both its rendered label and its committed text; [shifted], if
 * given, is what shift produces instead (an unrelated character, not a Unicode case pairing — see
 * [KeyDefinition.shiftedText]'s own doc for why that matters here). */
private fun devKey(unshifted: String, shifted: String? = null, popup: List<String> = emptyList(), widthWeight: Float = 1f) =
    KeyDefinition(
        label = unshifted,
        code = syntheticCode(),
        popupChars = popup,
        widthWeight = widthWeight,
        keyType = KeyType.CHARACTER,
        text = unshifted,
        shiftedText = shifted,
    )

/**
 * The standard Nepali "Traditional" keyboard: a legacy, non-phonetic, memorized key-position
 * layout (distinct from both Inscript and the Romanized/transliterated method — see
 * [dev.omakey.core.language.nepali.NepaliTransliterator] for that one) where each physical key
 * position produces a specific, often pre-formed-conjunct, Devanagari glyph — the same layout
 * long shipped with Preeti/Kantipur-style Nepali fonts and typewriters.
 *
 * Sourced from `ne-trad.mim` in the m17n input-method database (the layout IBus/fcitx ship for
 * Nepali), which documents itself as implementing the traditional layout published by Madan
 * Puraskar Pustakalaya (MPP) — Nepal's Unicode/font standards body. Cross-checked against two
 * independent readings of that file. **Flagged for native-speaker review before relying on it for
 * real typing** — it was reconstructed from a web-fetched transcription of that file rather than
 * visually proofread by a Nepali speaker; corrections are isolated one-line edits to [devKey]
 * calls below, not a rearchitecture.
 *
 * Only letters/matras/common conjuncts live on this 3-row main grid (everything linguistically
 * load-bearing for everyday words) — digits and the remaining top-row punctuation
 * (` ~ 1-0 - = and their shifted forms) are deferred to [NumberRow]/[ExtraRow], reached via the
 * top strip's Numbers tab, the same split [Layouts.QwertyEnUS] already uses for English (digits
 * and most punctuation aren't on its main grid either).
 */
object NepaliLayouts {
    val Traditional = KeyboardLayout(
        id = "nepali_traditional",
        rows = listOf(
            // q w e r t y u i o p [ ] \  — widened past QwertyEnUS's 10-key row (which fits
            // English precisely because English has nothing worth putting past "p") since the
            // Traditional layout's [, ], and \ positions carry genuinely common, load-bearing
            // characters (half-ra र्, the e-matra े, and the bare conjunct-forming virama ्) —
            // deferring them to a secondary tab the way English defers its own unused punctuation
            // would make ordinary words unreachable from the main grid, not just less convenient.
            KeyRow(
                listOf(
                    devKey("त्र", "त्त"), devKey("ध", "ड्ढ"), devKey("भ", "ऐ"), devKey("च", "द्ब"),
                    devKey("त", "ट्ट"), devKey("थ", "ठ्ठ"), devKey("ग", "ऊ"), devKey("ष", "क्ष"),
                    devKey("य", "इ"), devKey("उ", "ए"),
                    devKey("र्", widthWeight = 0.8f), devKey("े", widthWeight = 0.8f), devKey("्", "ं", widthWeight = 0.8f),
                ),
            ),
            // a s d f g h j k l ; ' — widened past QwertyEnUS's 9-key row (which deliberately
            // drops ;/') since here they carry स and the u/uu-matras (ु/ू), all common.
            KeyRow(
                listOf(
                    devKey("ब", "आ"), devKey("क", "ङ्क"), devKey("म", "ङ्ग"), devKey("ा", "ँ"),
                    devKey("न", "द्द"), devKey("ज", "झ"), devKey("व", "ो"), devKey("प", "फ"),
                    devKey("ि", "ी"), devKey("स", "ट्ठ", widthWeight = 0.85f), devKey("ु", "ू", widthWeight = 0.85f),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("⇧", SpecialKeyCode.SHIFT, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    devKey("श", "क्क"), devKey("ह", "ह्य"), devKey("अ", "ऋ"), devKey("ख", "ॐ"),
                    devKey("द", "ौ"), devKey("ल", "द्य"), devKey("ः", "ड्ड"),
                    KeyDefinition("⌫", SpecialKeyCode.BACKSPACE, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
            KeyRow(
                listOf(
                    KeyDefinition("?123", SpecialKeyCode.SYMBOLS, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                    KeyDefinition("😊", SpecialKeyCode.EXTENSIONS, widthWeight = 1f, keyType = KeyType.SPECIAL),
                    KeyDefinition(" ", SpecialKeyCode.SPACE, widthWeight = 4f, keyType = KeyType.SPECIAL),
                    // Danda (।) is Devanagari's sentence-ending mark — the direct equivalent of
                    // English's "." in the same grid slot. Popups cover the rest of the source
                    // mapping's punctuation tier that didn't earn a main-grid key of its own.
                    devKey("।", popup = listOf("ऽ", "!", "?", "-", "॥", "रु")),
                    KeyDefinition("⏎", SpecialKeyCode.ENTER, widthWeight = 1.5f, keyType = KeyType.SPECIAL),
                ),
            ),
        ),
    )

    /** Devanagari-digit counterpart to [Layouts.NumberRow], for the Numbers tab while a Nepali
     * input method is active. */
    val NumberRow = KeyRow(listOf("१", "२", "३", "४", "५", "६", "७", "८", "९", "०").map { devKey(it) })

    /** Devanagari-specific counterpart to [Layouts.SymbolsExtraRow] — the remaining top-row
     * punctuation from the source mapping that didn't get a main-grid slot: ॐ/ऽ/श्र/ङ are secondary
     * enough to live here rather than crowd the main letter grid. */
    val ExtraRow = KeyRow(
        listOf(
            devKey("ञ", "॥"), devKey("घ", "ज्ञ"), devKey("छ", "द्ध"), devKey("ठ", "ट"),
            devKey("ड", "ढ"), devKey("ण", "औ"), devKey("ओ", "ई"), devKey("ङ", "श्र"),
            devKey("ऽ", "ॐ"), devKey("रु"),
        ),
    )
}
