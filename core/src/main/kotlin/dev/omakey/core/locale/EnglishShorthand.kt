package dev.omakey.core.locale

/** English chat shorthand autocorrect must leave as typed — [LanguageProfile.English]'s
 * `keepAsTyped`. Closed-beta report: "pls" was silently corrected to "old" and "thx" to "the".
 * Gboard leaves these alone; so does omakey. Only words that are *not* already in the dictionary
 * matter here — a known word is never corrected anyway. No contraction-table key belongs here:
 * this list wins, so "yall" here meant it never became "y'all". */
internal object EnglishShorthand {
    val words: Set<String> = setOf(
        "pls", "plz", "thx", "thnx", "ty", "np", "idk", "imo", "imho", "btw", "lol", "omg", "brb",
        "ttyl", "tbh", "smh", "ikr", "fyi", "asap", "gonna", "wanna", "gotta", "ok", "k", "u", "ur",
        "ya",
    )
}
