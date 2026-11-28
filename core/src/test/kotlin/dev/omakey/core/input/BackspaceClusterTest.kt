package dev.omakey.core.input

import org.junit.Assert.assertEquals
import org.junit.Test

class BackspaceClusterTest {

    private fun deletes(before: String, expected: String) =
        assertEquals("deleting from \"$before\"", expected, before.takeLast(BackspaceCluster.length(before)))

    @Test fun `plain letters go one at a time`() = deletes("abc", "c")
    @Test fun `nothing to delete`() = assertEquals(0, BackspaceCluster.length(""))
    @Test fun `a lone emoji is both halves of its surrogate pair`() = deletes("hi 😀", "😀")
    @Test fun `skin tone goes with its emoji`() = deletes("ok 👍🏽", "👍🏽")
    @Test fun `variation selector goes with its emoji`() = deletes("I ❤️", "❤️")
    @Test fun keycap() = deletes("press 1️⃣", "1️⃣")
    @Test fun `zwj family is one emoji`() = deletes("x👨‍👩‍👧", "👨‍👩‍👧")
    @Test fun `zwj with a skin tone inside`() = deletes("dev 👩🏽‍💻", "👩🏽‍💻")
    @Test fun `flag is a pair of regional indicators`() = deletes("hi 🇳🇵", "🇳🇵")
    @Test fun `two flags side by side delete one at a time`() = deletes("🇳🇵🇯🇵", "🇯🇵")
    @Test fun `tag sequence flag`() = deletes("go 🏴󠁧󠁢󠁳󠁣󠁴󠁿", "🏴󠁧󠁢󠁳󠁣󠁴󠁿")
    @Test fun `combining marks still go one at a time`() = deletes("नमस्ते", "े")
    @Test fun `emoji after an emoji only takes the last`() = deletes("😀👍🏽", "👍🏽")

    @Test
    fun `TextEditor deletes a whole emoji and says what it removed`() {
        val connection = FakeInputConnection("dev 👩🏽‍💻")
        val removed = TextEditor(connectionProvider = { connection }).deleteCharacterBackward()
        assertEquals("dev ", connection.content)
        assertEquals("👩🏽‍💻", removed)
    }
}
