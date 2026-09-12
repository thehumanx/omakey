package dev.omakey.core.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The bridge between omakey's own theme model and Compose.
 *
 * These three helpers were previously copy-pasted into four files across three modules — the
 * keyboard UI, the settings UI, and both built-in extensions — each with its own slightly different
 * signature. Grid borders in particular had drifted into three variants of the same six lines,
 * which matters because border drawing is the one thing in this app that has already produced a
 * "why is this edge twice as thick" bug more than once. Sharing them means a fix lands everywhere
 * rather than in whichever copy the reporter happened to hit.
 *
 * They live in `core` because [ColorSpec] and [GridBorderWidth] do, and because both consumers
 * already depend on it.
 */

/** [ColorSpec] is stored as a packed ARGB long so themes serialise as plain JSON; Compose wants its
 * own value class. */
fun ColorSpec.toComposeColor(): Color = Color(argb.toInt())

fun GridBorderWidth.toDp(): Dp = when (this) {
    GridBorderWidth.SM -> 1.dp
    GridBorderWidth.MD -> 1.5.dp
    GridBorderWidth.LG -> 2.5.dp
}

/** Default grid stroke, for surfaces that draw a border before any theme is resolved. */
val DEFAULT_GRID_BORDER_WIDTH: Dp = 1.5.dp

/**
 * Grid-mode cell border: each cell draws only its own right (and usually bottom) edge, so adjacent
 * cells never double up on the seam between them.
 *
 * That single-draw model is the whole design, and both opt-outs exist because of real doubled-edge
 * bugs:
 *
 *  - **[includeBottom] = false** for a cell that is the only row in its region and sits directly
 *    above another self-bordering region — a suggestion chip, tool button or number key inside
 *    `TopStrip`, which sits flush above `KeyGrid`. `KeyGrid` already draws that seam; a cell
 *    contributing its own bottom edge there made the line thicker *only where a chip happened to
 *    exist*, which is why it was reported as "the bottom border is thicker when there's a
 *    suggestion".
 *  - **[includeLeft] = true** for the leftmost cell in a grid, which has no neighbour to its left
 *    to draw that edge for it.
 *
 * Drawn with `drawBehind` on the same element that owns the background — not on an ancestor, which
 * puts the stroke outside the filled area.
 */
fun Modifier.gridCellBorder(
    color: Color,
    strokeWidth: Dp = DEFAULT_GRID_BORDER_WIDTH,
    includeBottom: Boolean = true,
    includeLeft: Boolean = false,
): Modifier = this.drawBehind {
    val strokePx = strokeWidth.toPx()
    val half = strokePx / 2f
    drawLine(color, Offset(size.width - half, 0f), Offset(size.width - half, size.height), strokePx)
    if (includeBottom) {
        drawLine(color, Offset(0f, size.height - half), Offset(size.width, size.height - half), strokePx)
    }
    if (includeLeft) {
        drawLine(color, Offset(half, 0f), Offset(half, size.height), strokePx)
    }
}
