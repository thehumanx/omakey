package dev.omakey.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetTransferTest {

    private val email = Snippet(1, "Email", "bibek@example.com", secret = false, position = 0, shortcut = "@@")
    private val note = Snippet(2, "", "Thanks,\nBibek", secret = false, position = 1)
    private val bank = Snippet(3, "Bank", "hunter2!X", secret = true, position = 2)

    @Test
    fun `round trip`() {
        val json = SnippetTransfer.encode(listOf(email, note))
        assertEquals(
            listOf(SnippetTransfer.Entry("Email", "bibek@example.com", "@@"), SnippetTransfer.Entry("", "Thanks,\nBibek", null)),
            SnippetTransfer.decode(json),
        )
    }

    @Test
    fun `secrets are never written, even if handed in with their text`() {
        val json = SnippetTransfer.encode(listOf(email, bank))
        assertFalse(json.contains("hunter2"))
        assertFalse(json.contains("Bank"))
    }

    @Test
    fun `a malformed or foreign file is refused`() {
        assertNull(SnippetTransfer.decode("not json"))
        assertNull(SnippetTransfer.decode("""{"format":"something-else","version":1,"snippets":[]}"""))
        assertNull(SnippetTransfer.decode("""{"format":"omakey-snippets","version":99,"snippets":[]}"""))
    }

    @Test
    fun `unknown keys from a newer version are ignored`() =
        assertEquals(
            listOf(SnippetTransfer.Entry("A", "a", null)),
            SnippetTransfer.decode("""{"format":"omakey-snippets","version":1,"extra":true,"snippets":[{"name":"A","text":"a","colour":"red"}]}"""),
        )

    @Test
    fun `entries with no text are dropped`() =
        assertEquals(
            emptyList<SnippetTransfer.Entry>(),
            SnippetTransfer.decode("""{"format":"omakey-snippets","version":1,"snippets":[{"name":"A","text":"  "}]}"""),
        )

    @Test
    fun `import skips duplicates and drops clashing codes`() {
        val incoming = listOf(
            SnippetTransfer.Entry("Email", "bibek@example.com", "@@"), // duplicate of email
            SnippetTransfer.Entry("Work", "work@example.com", "@@"), // code clash with email
            SnippetTransfer.Entry("Home", "Kathmandu", "home"),
            SnippetTransfer.Entry("Home 2", "Pokhara", "HOME"), // clashes within the file: first wins
        )
        val plan = SnippetTransfer.plan(listOf(email, note), incoming)
        assertEquals(
            listOf(
                SnippetTransfer.Entry("Work", "work@example.com", null),
                SnippetTransfer.Entry("Home", "Kathmandu", "home"),
                SnippetTransfer.Entry("Home 2", "Pokhara", null),
            ),
            plan.toAdd,
        )
        assertEquals(1, plan.skipped)
        assertEquals(2, plan.codesDropped)
    }

    @Test
    fun `text over the limit is cut, and the import says how many`() {
        val long = SnippetTransfer.Entry("Long", "x".repeat(SnippetRules.MAX_TEXT + 5), null)
        val plan = SnippetTransfer.plan(emptyList(), listOf(long))
        assertEquals(SnippetRules.MAX_TEXT, plan.toAdd.single().text.length)
        assertEquals(1, plan.shortened)
    }
}
