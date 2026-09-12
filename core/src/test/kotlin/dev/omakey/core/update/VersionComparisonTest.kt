package dev.omakey.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison that decides whether a user is told an update exists. Previously private to
 * `GithubReleaseUpdateChecker` and untested, and silently wrong for any tag that wasn't purely
 * numeric.
 */
class VersionComparisonTest {

    @Test
    fun `a higher patch version is newer`() {
        assertTrue(isNewerVersion("4.0.1", "4.0.0"))
        assertFalse(isNewerVersion("4.0.0", "4.0.1"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(isNewerVersion("4.0.0", "4.0.0"))
    }

    @Test
    fun `major and minor outrank patch`() {
        assertTrue(isNewerVersion("5.0.0", "4.9.9"))
        assertTrue(isNewerVersion("4.1.0", "4.0.9"))
    }

    @Test
    fun `multi-digit parts compare numerically, not lexically`() {
        // "10" sorts below "9" as a string; parsing to ints is the whole point.
        assertTrue(isNewerVersion("4.10.0", "4.9.0"))
    }

    @Test
    fun `missing parts count as zero`() {
        assertFalse(isNewerVersion("4.0", "4.0.0"))
        assertFalse(isNewerVersion("4.0.0", "4.0"))
        assertTrue(isNewerVersion("4.0.1", "4.0"))
    }

    @Test
    fun `a leading v is ignored`() {
        // Release tags carry it; versionName does not.
        assertTrue(isNewerVersion("v4.0.1", "4.0.0"))
        assertFalse(isNewerVersion("v4.0.0", "4.0.0"))
    }

    @Test
    fun `a release is newer than its own pre-release`() {
        // The bug this replaces: "rc1" parsed as 0, so these compared equal and the person running
        // a release candidate was never told the final build existed.
        assertTrue(isNewerVersion("4.0.0", "4.0.0-rc1"))
    }

    @Test
    fun `a pre-release is not newer than the release it precedes`() {
        assertFalse(isNewerVersion("4.0.0-rc1", "4.0.0"))
    }

    @Test
    fun `later pre-releases beat earlier ones`() {
        assertTrue(isNewerVersion("4.0.0-rc2", "4.0.0-rc1"))
        assertFalse(isNewerVersion("4.0.0-rc1", "4.0.0-rc2"))
    }

    @Test
    fun `numbers still win over suffixes`() {
        // A pre-release of a higher version is genuinely newer than a lower final release.
        assertTrue(isNewerVersion("4.1.0-rc1", "4.0.0"))
        assertFalse(isNewerVersion("4.0.0", "4.1.0-rc1"))
    }

    @Test
    fun `a suffix glued to the last number is still a suffix`() {
        // Tags in the wild do both "4.0.0-rc1" and "4.0.0rc1".
        assertTrue(isNewerVersion("4.0.0", "4.0.0rc1"))
        assertFalse(isNewerVersion("4.0.0rc1", "4.0.0"))
    }

    @Test
    fun `build metadata does not make a version newer than itself`() {
        assertFalse(isNewerVersion("4.0.0+build7", "4.0.0+build7"))
    }

    @Test
    fun `malformed input does not throw`() {
        // The input is a tag name straight out of a network response; it must never crash the
        // background worker.
        assertFalse(isNewerVersion("", ""))
        assertFalse(isNewerVersion("not-a-version", "not-a-version"))
    }
}
