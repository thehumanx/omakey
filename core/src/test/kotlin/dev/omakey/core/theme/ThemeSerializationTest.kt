package dev.omakey.core.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSerializationTest {

    @Test
    fun `theme survives a JSON round trip`() {
        val original = Presets.Accent
        val json = ThemeSerializer.toJson(original)
        val restored = ThemeSerializer.fromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun `all preset fields round trip correctly`() {
        for (preset in Presets.all) {
            val restored = ThemeSerializer.fromJson(ThemeSerializer.toJson(preset))
            assertEquals(preset.id, restored.id)
            assertEquals(preset.keyShape, restored.keyShape)
            assertEquals(preset.keyBackground, restored.keyBackground)
        }
    }

    /**
     * A theme saved by an older build carries `keySpacingDp`, a field that has since been removed
     * because no rendering code ever read it.
     *
     * Deleting a field from a `@Serializable` class is the dangerous direction: by default
     * kotlinx.serialization *throws* on a key it doesn't recognise, which here would mean every
     * custom theme on an existing device failing to load on upgrade. `ignoreUnknownKeys = true` is
     * what makes it safe, and this test is what keeps that setting from being removed as
     * apparently-unused tidying.
     */
    @Test
    fun `a theme saved before keySpacingDp was removed still loads`() {
        val storedByOlderBuild = ThemeSerializer.toJson(Presets.Dark)
            .replaceFirst("{", """{"keySpacingDp":6.5,""")

        val restored = ThemeSerializer.fromJson(storedByOlderBuild)

        assertEquals(Presets.Dark, restored)
    }
}
