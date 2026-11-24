package dev.omakey.core.snippets

/**
 * A saved piece of text the user types with one tap from the Snippets page above the keys
 * (Fleksy's "Hotkeys", done better; see docs/superpowers/specs/2026-11-23-snippets-design.md).
 *
 * [text] is null for a [secret] snippet until it is decrypted at the moment it is typed or shown;
 * a secret's plain text is never kept in a list or written anywhere. [toString] leaves the text
 * out, so a snippet that ends up in a log line can't leak what it holds.
 */
data class Snippet(
    val id: Long,
    val name: String,
    val text: String?,
    val secret: Boolean,
    val position: Int,
    /** Typing this (step 2) offers the snippet in the suggestion bar. Plain snippets only. */
    val shortcut: String? = null,
) {
    override fun toString(): String = "Snippet(id=$id, name=$name, secret=$secret, position=$position)"
}

/**
 * Short codes (step 2): type a code, and the suggestion bar offers its snippet; tapping the offer
 * replaces the code. Space never expands one, so a code that is also a word stays harmless. A code
 * is whatever sits between the last whitespace and the cursor, not a "word", so symbol codes like
 * `@@` work and word tracking (and the English golden file) are untouched.
 */
object SnippetCodes {

    const val MAX_LENGTH = 32

    fun tokenBeforeCursor(textBefore: String): String = textBefore.takeLastWhile { !it.isWhitespace() }

    /** The plain snippet whose code is the token before the cursor, ignoring case. */
    fun match(textBefore: String, snippets: List<Snippet>): Snippet? {
        val token = tokenBeforeCursor(textBefore)
        if (token.isEmpty()) return null
        return snippets.firstOrNull { !it.secret && it.shortcut?.equals(token, ignoreCase = true) == true }
    }

    /**
     * Whether an offer made for snippet [id] after [code] was typed still stands: the snippet is
     * still there, still plain, and still has that code. Checked again at tap time, since the list
     * can change between the offer and the tap (made secret, code edited, deleted).
     */
    fun stillOffered(id: Long, code: String, snippets: List<Snippet>): Boolean =
        snippets.any { it.id == id && !it.secret && it.shortcut?.equals(code, ignoreCase = true) == true }

    /** An error for the form, or null when [code] can be saved (blank means no code). */
    fun validate(code: String, snippets: List<Snippet>, selfId: Long?): String? {
        if (code.isBlank()) return null
        if (code.any { it.isWhitespace() }) return "No spaces in a short code"
        if (code.length > MAX_LENGTH) return "Up to $MAX_LENGTH characters"
        val clash = snippets.firstOrNull { it.id != selfId && !it.secret && it.shortcut?.equals(code, ignoreCase = true) == true }
        return clash?.let { "Already used by ${SnippetRules.chipLabel(it)}" }
    }
}

/** The rules behind the Snippets page and its edit form, kept here so they can be tested. */
object SnippetRules {

    const val MAX_TEXT = 2000
    const val MASK = "••••"
    private const val LABEL_CHARS = 24

    /**
     * What a snippet's chip shows. A secret shows the mask and its name and never its text. A plain
     * snippet shows its name, or its first line cut to fit a chip when it has none.
     */
    fun chipLabel(s: Snippet): String {
        if (s.secret) return "$MASK ${s.name}"
        if (s.name.isNotBlank()) return s.name
        val line = s.text.orEmpty().lineSequence().firstOrNull().orEmpty()
        return if (line.length > LABEL_CHARS) line.take(LABEL_CHARS) + "…" else line
    }

    /**
     * The snippets the keyboard shows. While the phone is locked (a reply from a lock-screen
     * notification), secrets are left out: the phone's lock is what protects them, so a locked
     * phone must not offer them.
     */
    fun visible(all: List<Snippet>, deviceLocked: Boolean): List<Snippet> =
        if (deviceLocked) all.filterNot { it.secret } else all

    /** An error to show on the form, or null when the snippet can be saved. */
    fun validate(name: String, text: String, secret: Boolean): String? = when {
        text.isBlank() -> "Add what gets typed"
        text.length > MAX_TEXT -> "Up to $MAX_TEXT characters"
        secret && name.isBlank() -> "Give the password a button label, like Bank"
        else -> null
    }

    /**
     * Whether plain text looks like a password, to offer "Make it secret?" on save. It only ever
     * warns: one token of 8–64 characters, no space or `@` (so not an email or a phone number),
     * and at least three of lowercase, uppercase, digit and symbol.
     */
    fun looksLikePassword(text: String): Boolean {
        if (text.length !in 8..64 || text.any { it.isWhitespace() } || '@' in text) return false
        val classes = listOf(
            text.any { it.isLowerCase() },
            text.any { it.isUpperCase() },
            text.any { it.isDigit() },
            text.any { !it.isLetterOrDigit() },
        ).count { it }
        return classes >= 3
    }
}
