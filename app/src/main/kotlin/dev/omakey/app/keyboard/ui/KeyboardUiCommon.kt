package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.toDp
import dev.omakey.core.theme.gridCellBorder
import dev.omakey.core.theme.ColorSpec
import dev.omakey.core.theme.OmakeyTheme

/*
 * Shared drawing helpers and dimensions for the keyboard surface.
 *
 * Split out of KeyboardRoot.kt, which was 2748 lines. Everything here is used by at least two of
 * the files carved out of it — that is what makes it common rather than local to one of them.
 */


internal const val SUGGESTION_STRIP_HEIGHT_DP = 44

/** Height of the floating keyboard's move handle. Only floating keyboards have one, so it is added
 * to the placement container's height in that mode alone — see [FloatingMoveHandle]. */
internal const val FLOATING_HANDLE_HEIGHT_DP = 22

/** Opacity for every suggestion-strip candidate except the currently-focused one (index 0) —
 * see [SuggestionsTabContent]. */
internal const val SUGGESTION_FADED_ALPHA = 0.45f

/** Grid mode's border thickness — same everywhere, cell-to-cell and at the true outer edges.
 *
 * History, briefly: a "double-width outer border" approach (each cell drew a full 4-sided border,
 * adjacent cells doubling up on shared edges, so outer edges needed a manually-matched 2x-width
 * compensating border) kept needing new special cases every time another seam turned up thin or
 * double-thick. Replaced with a single-draw model — every cell ([gridCellBorder]) draws *only* its
 * right and bottom edges, so no seam can double up by construction — plus a region-level top+left
 * "outer edge" border on the wrapping ancestor for the two sides no cell ever draws. That
 * region-level border used `drawBehind`, which turned out to be the wrong tool: a `drawBehind` on
 * an *ancestor* draws before its children, so a child's own full-bounds opaque background (e.g.
 * `TopStrip`'s own `.background()`, or the top-left key's own fill) painted directly over it every
 * time, real bug — reported as "missing with no suggestions on screen," but it was actually always
 * covered regardless of content. `drawWithContent` (paint the border *after* `drawContent()`) was
 * the theoretically-correct fix and should have worked, but didn't reliably show up in practice
 * either, for reasons that weren't worth continuing to chase.
 *
 * Landed on the simplest reliable answer instead: skip the ancestor-level border entirely.
 * `TopStrip`, `KeyGrid`, `ExtensionPanelSlot`'s own Column/header Row, and the emoji panel's
 * bottom bar each call plain, official `Modifier.border()` **directly on themselves** (the exact
 * same element that also owns their own background) — `border()` is guaranteed to draw on top of
 * all of an element's own descendant content regardless of nesting depth, so there's no ordering
 * ambiguity left to get wrong. Each region's own cells still draw right+bottom only, so a region's
 * self-border only *actually* contributes new pixels on its top/left (its right/bottom edges just
 * redundantly overlap the rightmost/bottommost cell's own edge — same pixels, not a visible
 * doubling). The one accepted trade-off: two *different*, adjacent self-bordering regions (e.g.
 * `TopStrip`'s bottom against `KeyGrid`'s top) do genuinely double up at that shared seam, since
 * both are now separately, fully self-bordered — a real but minor, purely cosmetic inconsistency,
 * traded for a border that's actually guaranteed to render.
 *
 * 0.75dp also read as too thin once actually on-device (real user feedback) — bumped to 1.5dp. */
internal val GRID_BORDER_WIDTH = 1.5.dp

/** Maps the user's SM/MD/LG choice (`OmakeyTheme.gridBorderWidth`) to an actual stroke width —
 * kept in the render layer, not `core`, since `Dp` is a Compose UI type (see `GridBorderWidth`'s
 * own doc in `OmakeyTheme.kt`). MD matches [GRID_BORDER_WIDTH], the value every border already
 * used before this became user-configurable. */

/** [includeBottom] defaults to true (the normal case — a cell inside a multi-row grid needs to
 * own the seam to the row below it, since nothing else will). Pass false for a cell that's the
 * *only* row in its own region and sits directly above another self-bordering region (e.g. a
 * suggestion chip, tool button, or `NumbersTabContent`'s number key — all single-row content
 * inside `TopStrip`, which sits flush above `KeyGrid`) — real bug, fixed: those cells' own bottom
 * edge was an *extra*, uncoordinated contributor at that exact seam on top of `KeyGrid`'s own top
 * self-border, doubling it up specifically wherever a cell/chip/key actually existed in the strip
 * (reported as "the bottom border is thicker when there's a suggestion" — an empty strip has no
 * chips to contribute the extra stroke, so it looked correct only by having nothing there). */

/** `TopStrip` sits flush (zero gap) directly above `KeyGrid`, which already self-borders its own
 * top edge — a second full 4-sided border on `TopStrip` would double up at that one shared seam
 * (the exact inconsistency reported after landing the self-border fix). Skips the bottom edge for
 * exactly that reason. Still applied directly on `TopStrip`'s own element (not a separate
 * ancestor) via `drawWithContent`, same "must be the same element that owns the background" lesson
 * as everywhere else here — this one just also needs to leave one side out. */
internal fun Modifier.gridBorderExceptBottom(color: Color, strokeWidth: androidx.compose.ui.unit.Dp = GRID_BORDER_WIDTH): Modifier = this.drawWithContent {
    drawContent()
    val strokePx = strokeWidth.toPx()
    val half = strokePx / 2f
    drawLine(color, Offset(half, 0f), Offset(half, size.height), strokePx)
    drawLine(color, Offset(size.width - half, 0f), Offset(size.width - half, size.height), strokePx)
    drawLine(color, Offset(0f, half), Offset(size.width, half), strokePx)
}
