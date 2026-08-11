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
}
