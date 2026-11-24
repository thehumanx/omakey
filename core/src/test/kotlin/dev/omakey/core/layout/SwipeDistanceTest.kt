package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeDistanceTest {

    @Test
    fun `a full keyboard needs 18 percent of its height`() =
        assertEquals(36f, SwipeDistance.verticalPx(gridHeightPx = 200f, drawnRows = 4, baseRows = 4, sensitivity = 1f), 0.01f)

    @Test
    fun `compact needs the same distance as the full keyboard, not a quarter less`() =
        assertEquals(36f, SwipeDistance.verticalPx(gridHeightPx = 150f, drawnRows = 3, baseRows = 4, sensitivity = 1f), 0.01f)

    @Test
    fun `sensitivity still scales it`() =
        assertEquals(72f, SwipeDistance.verticalPx(gridHeightPx = 200f, drawnRows = 4, baseRows = 4, sensitivity = 2f), 0.01f)

    @Test
    fun `a resize drag on a compact keyboard moves its edge with the finger`() =
        assertEquals(40f, SwipeDistance.fullHeightDelta(visibleDeltaDp = 30f, drawnRows = 3, baseRows = 4), 0.01f)
}
