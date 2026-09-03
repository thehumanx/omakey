package dev.omakey.core.icons

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Hand-picked icons from the Phosphor icon set (`fill` weight, MIT-licensed,
 * https://phosphoricons.com), built directly from their raw SVG path data (256x256 viewBox)
 * rather than pulled in as a library dependency — Compose has no first-class way to load an
 * arbitrary external icon *set*, only individual named icons wired up as [ImageVector]s, and this
 * project only needs a handful. [Icon]'s `tint` fully recolors these via `ColorFilter` regardless
 * of the placeholder fill color baked into the path data below, so the actual color doesn't
 * matter — it exists only so the path renders as solid/opaque geometry. */
private fun pathIcon(pathData: String): ImageVector {
    val nodes = PathParser().parsePathString(pathData).toNodes()
    return ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 256f,
        viewportHeight = 256f,
    ).addPath(pathData = nodes, fill = SolidColor(androidx.compose.ui.graphics.Color.Black)).build()
}

/** `arrow-fat-up-fill` — one-shot shift (capitalizes just the next letter). */
val PhosphorShift: ImageVector by lazy {
    pathIcon(
        "M231.39,123.06A8,8,0,0,1,224,128H184v80a16,16,0,0,1-16,16H88a16,16,0,0,1-16-16V128H32a8,8,0,0,1-5.66-13.66l96-96a8,8,0,0,1,11.32,0l96,96A8,8,0,0,1,231.39,123.06Z",
    )
}

/** `arrow-fat-line-up-fill` — caps lock engaged (long-press shift); the underline distinguishes
 * it from the plain one-shot arrow above at a glance, same idea as a physical caps-lock LED. */
val PhosphorShiftLocked: ImageVector by lazy {
    pathIcon(
        "M184,216a8,8,0,0,1-8,8H80a8,8,0,0,1,0-16h96A8,8,0,0,1,184,216Zm45.66-101.66-96-96a8,8,0,0,0-11.32,0l-96,96A8,8,0,0,0,32,128H72v56a8,8,0,0,0,8,8h96a8,8,0,0,0,8-8V128h40a8,8,0,0,0,5.66-13.66Z",
    )
}

/** `backspace-fill`. */
val PhosphorBackspace: ImageVector by lazy {
    pathIcon(
        "M216,40H68.53a16.12,16.12,0,0,0-13.72,7.77L9.14,123.88a8,8,0,0,0,0,8.24l45.67,76.11h0A16.11,16.11,0,0,0,68.53,216H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40ZM165.66,146.34a8,8,0,0,1-11.32,11.32L136,139.31l-18.35,18.35a8,8,0,0,1-11.31-11.32L124.69,128l-18.35-18.34a8,8,0,1,1,11.31-11.32L136,116.69l18.34-18.35a8,8,0,0,1,11.32,11.32L147.31,128Z",
    )
}

/** `key-return-fill` — the default Enter key (plain newline; no `EditorInfo` action). */
val PhosphorEnter: ImageVector by lazy {
    pathIcon(
        "M216,40H40A16,16,0,0,0,24,56V200a16,16,0,0,0,16,16H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40Zm-32,96a8,8,0,0,1-8,8H99.31l10.35,10.34a8,8,0,0,1-11.32,11.32l-24-24a8,8,0,0,1,0-11.32l24-24a8,8,0,0,1,11.32,11.32L99.31,128H168V104a8,8,0,0,1,16,0Z",
    )
}

/** `check-fill` — Enter key when the field declares [android.view.inputmethod.EditorInfo.IME_ACTION_DONE]. */
val PhosphorCheck: ImageVector by lazy {
    pathIcon(
        "M216,40H40A16,16,0,0,0,24,56V200a16,16,0,0,0,16,16H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40ZM205.66,85.66l-96,96a8,8,0,0,1-11.32,0l-40-40a8,8,0,0,1,11.32-11.32L104,164.69l90.34-90.35a8,8,0,0,1,11.32,11.32Z",
    )
}

/** `check-bold` — a plain checkmark stroke, no enclosing box/circle, unlike [PhosphorCheck]
 * (which is specifically the Enter key's rounded-square glyph). Used for "this step is done"
 * status indicators (Settings' Setup section) — a proper vector icon instead of a "✓" text glyph,
 * which read as an old-school/inconsistent style choice against the rest of the icon set. */
val PhosphorCheckmark: ImageVector by lazy {
    pathIcon(
        "M104,192a12,12,0,0,1-8.49-3.51l-64-64a12,12,0,0,1,17-17L104,163.51,207.51,60a12,12,0,0,1,17,17l-112,112A12,12,0,0,1,104,192Z",
    )
}

/** `arrow-right-fill` — Enter key for GO/NEXT actions. */
val PhosphorArrowRight: ImageVector by lazy {
    pathIcon("M221.66,133.66l-72,72A8,8,0,0,1,136,200V136H40a8,8,0,0,1,0-16h96V56a8,8,0,0,1,13.66-5.66l72,72A8,8,0,0,1,221.66,133.66Z")
}

/** `arrow-left-fill` — Enter key for the PREVIOUS action. */
val PhosphorArrowLeft: ImageVector by lazy {
    pathIcon("M224,128a8,8,0,0,1-8,8H120v64a8,8,0,0,1-13.66,5.66l-72-72a8,8,0,0,1,0-11.32l72-72A8,8,0,0,1,120,56v64h96A8,8,0,0,1,224,128Z")
}

/** `magnifying-glass-fill` — Enter key for the SEARCH action. */
val PhosphorSearch: ImageVector by lazy {
    pathIcon(
        "M168,112a56,56,0,1,1-56-56A56,56,0,0,1,168,112Zm61.66,117.66a8,8,0,0,1-11.32,0l-50.06-50.07a88,88,0,1,1,11.32-11.31l50.06,50.06A8,8,0,0,1,229.66,229.66ZM112,184a72,72,0,1,0-72-72A72.08,72.08,0,0,0,112,184Z",
    )
}

/** `paper-plane-right-fill` — Enter key for the SEND action. */
val PhosphorSend: ImageVector by lazy {
    pathIcon(
        "M240,127.89a16,16,0,0,1-8.18,14L63.9,237.9A16.15,16.15,0,0,1,56,240a16,16,0,0,1-15-21.33l27-79.95A4,4,0,0,1,71.72,136H144a8,8,0,0,0,8-8.53,8.19,8.19,0,0,0-8.26-7.47h-72a4,4,0,0,1-3.79-2.72l-27-79.94A16,16,0,0,1,63.84,18.07l168,95.89A16,16,0,0,1,240,127.89Z",
    )
}

/** `selection-all-fill` — Tools tab "Select all". */
val PhosphorSelectAll: ImageVector by lazy {
    pathIcon(
        "M104,40a8,8,0,0,1,8-8h32a8,8,0,0,1,0,16H112A8,8,0,0,1,104,40Zm40,168H112a8,8,0,0,0,0,16h32a8,8,0,0,0,0-16ZM208,32H184a8,8,0,0,0,0,16h24V72a8,8,0,0,0,16,0V48A16,16,0,0,0,208,32Zm8,72a8,8,0,0,0-8,8v32a8,8,0,0,0,16,0V112A8,8,0,0,0,216,104Zm0,72a8,8,0,0,0-8,8v24H184a8,8,0,0,0,0,16h24a16,16,0,0,0,16-16V184A8,8,0,0,0,216,176ZM40,152a8,8,0,0,0,8-8V112a8,8,0,0,0-16,0v32A8,8,0,0,0,40,152Zm32,56H48V184a8,8,0,0,0-16,0v24a16,16,0,0,0,16,16H72a8,8,0,0,0,0-16ZM40,80a8,8,0,0,0,8-8V48H72a8,8,0,0,0,0-16H48A16,16,0,0,0,32,48V72A8,8,0,0,0,40,80Zm144,96V80a8,8,0,0,0-8-8H80a8,8,0,0,0-8,8v96a8,8,0,0,0,8,8h96A8,8,0,0,0,184,176Z",
    )
}

/** `copy-fill` — Tools tab "Copy". */
val PhosphorCopy: ImageVector by lazy {
    pathIcon("M216,32H88a8,8,0,0,0-8,8V80H40a8,8,0,0,0-8,8V216a8,8,0,0,0,8,8H168a8,8,0,0,0,8-8V176h40a8,8,0,0,0,8-8V40A8,8,0,0,0,216,32Zm-8,128H176V88a8,8,0,0,0-8-8H96V48H208Z")
}

/** `scissors-fill` — Tools tab "Cut". */
val PhosphorCut: ImageVector by lazy {
    pathIcon(
        "M236.52,187.09l-143-97.87a36,36,0,1,0-14.38,17.27l21.39,21.69L79.15,149.54l0,0a35.91,35.91,0,1,0,14.38,17.27l26.91-18.41L170,198.64a32.26,32.26,0,0,0,22.7,9.37,31.52,31.52,0,0,0,4.11-.27l.28,0,36.27-6.11a8,8,0,0,0,3.19-14.5Zm-162.38-97A20,20,0,1,1,80,76,20,20,0,0,1,74.14,90.13Zm0,104A20,20,0,1,1,80,180,20,20,0,0,1,74.14,194.15Zm61-101.5L169.94,57.4a32.19,32.19,0,0,1,26.84-9.14l.28,0,36,6.07a8.21,8.21,0,0,1,6.09,4.42,8,8,0,0,1-2.67,10.12l-69.93,47.85a4,4,0,0,1-4.51,0l-26.31-18A4,4,0,0,1,135.18,92.65Z",
    )
}

/** `clipboard-fill` — Tools tab "Paste". */
val PhosphorPaste: ImageVector by lazy {
    pathIcon("M200,32H163.74a47.92,47.92,0,0,0-71.48,0H56A16,16,0,0,0,40,48V216a16,16,0,0,0,16,16H200a16,16,0,0,0,16-16V48A16,16,0,0,0,200,32Zm-72,0a32,32,0,0,1,32,32H96A32,32,0,0,1,128,32Z")
}

/** `clipboard-text-fill` — Tools tab "Clipboard" (history panel) — deliberately distinct from
 * [PhosphorPaste]'s plain clipboard glyph (the lines suggest "a list," i.e. history). */
val PhosphorClipboardHistory: ImageVector by lazy {
    pathIcon(
        "M200,32H163.74a47.92,47.92,0,0,0-71.48,0H56A16,16,0,0,0,40,48V216a16,16,0,0,0,16,16H200a16,16,0,0,0,16-16V48A16,16,0,0,0,200,32Zm-72,0a32,32,0,0,1,32,32H96A32,32,0,0,1,128,32Zm32,128H96a8,8,0,0,1,0-16h64a8,8,0,0,1,0,16Zm0-32H96a8,8,0,0,1,0-16h64a8,8,0,0,1,0,16Z",
    )
}

/** `arrow-counter-clockwise-fill` — Tools tab "Undo". */
val PhosphorUndo: ImageVector by lazy {
    pathIcon(
        "M224,128a96,96,0,0,1-94.71,96H128A95.38,95.38,0,0,1,62.1,197.8a8,8,0,0,1,11-11.63A80,80,0,1,0,71.43,71.39a3.07,3.07,0,0,1-.26.25L60.63,81.29l17,17A8,8,0,0,1,72,112H24a8,8,0,0,1-8-8V56A8,8,0,0,1,29.66,50.3L49.31,70,60.25,60A96,96,0,0,1,224,128Z",
    )
}

/** `arrow-clockwise-fill` — Tools tab "Redo". */
val PhosphorRedo: ImageVector by lazy {
    pathIcon(
        "M240,56v48a8,8,0,0,1-8,8H184a8,8,0,0,1-5.66-13.66l17-17-10.55-9.65-.25-.24a80,80,0,1,0-1.67,114.78,8,8,0,1,1,11,11.63A95.44,95.44,0,0,1,128,224h-1.32A96,96,0,1,1,195.75,60l10.93,10L226.34,50.3A8,8,0,0,1,240,56Z",
    )
}

/** `gear-fill` — the Symbols1/Symbols2 layouts' Settings key (see `SpecialKeyCode.SETTINGS`),
 * which sits in the same slot QwertyEnUS's emoji-launcher key occupies so switching between
 * letters and symbols doesn't shift every other key's width. */
val PhosphorGear: ImageVector by lazy {
    pathIcon(
        "M216,130.16q.06-2.16,0-4.32l14.92-18.64a8,8,0,0,0,1.48-7.06,107.6,107.6,0,0,0-10.88-26.25,8,8,0,0,0-6-3.93l-23.72-2.64q-1.48-1.56-3-3L186,40.54a8,8,0,0,0-3.94-6,107.29,107.29,0,0,0-26.25-10.86,8,8,0,0,0-7.06,1.48L130.16,40Q128,40,125.84,40L107.2,25.11a8,8,0,0,0-7.06-1.48A107.6,107.6,0,0,0,73.89,34.51a8,8,0,0,0-3.93,6L67.32,64.27q-1.56,1.49-3,3L40.54,70a8,8,0,0,0-6,3.94,107.71,107.71,0,0,0-10.87,26.25,8,8,0,0,0,1.49,7.06L40,125.84Q40,128,40,130.16L25.11,148.8a8,8,0,0,0-1.48,7.06,107.6,107.6,0,0,0,10.88,26.25,8,8,0,0,0,6,3.93l23.72,2.64q1.49,1.56,3,3L70,215.46a8,8,0,0,0,3.94,6,107.71,107.71,0,0,0,26.25,10.87,8,8,0,0,0,7.06-1.49L125.84,216q2.16.06,4.32,0l18.64,14.92a8,8,0,0,0,7.06,1.48,107.21,107.21,0,0,0,26.25-10.88,8,8,0,0,0,3.93-6l2.64-23.72q1.56-1.48,3-3L215.46,186a8,8,0,0,0,6-3.94,107.71,107.71,0,0,0,10.87-26.25,8,8,0,0,0-1.49-7.06ZM128,168a40,40,0,1,1,40-40A40,40,0,0,1,128,168Z",
    )
}

/** `eye-closed-bold` — the Tools tab's incognito toggle ("stop remembering what I type"). Shown
 * filled/highlighted while incognito is active; see `KeyboardUiState.incognito`. */
val PhosphorIncognito: ImageVector by lazy {
    pathIcon(
        "M53.92,34.62A8,8,0,1,0,42.08,45.38L61.32,66.55C25,88.84,9.38,123.2,8.69,124.76a8,8,0,0,0,0,6.5c.35.79,8.82,19.57,27.65,38.4C61.43,194.74,93.12,208,128,208a127.11,127.11,0,0,0,52.07-10.83l22,24.21a8,8,0,1,0,11.84-10.76Zm47.33,75.84,36.63,40.29a32,32,0,0,1-36.63-40.29ZM128,192c-30.78,0-57.67-11.19-79.93-33.25A133.47,133.47,0,0,1,25,128c4.69-8.79,19.66-33.39,47.35-49.38l18,19.75a48,48,0,0,0,63.66,70l14.73,16.2A112,112,0,0,1,128,192Zm6-95.43a8,8,0,0,1,3-15.72,48.16,48.16,0,0,1,38.77,42.72,8,8,0,0,1-7.22,8.71,6.39,6.39,0,0,1-.75,0,8,8,0,0,1-8-7.26A32.09,32.09,0,0,0,134,96.57Zm113.28,34.69c-.42.94-10.55,23.37-33.36,43.8a8,8,0,1,1-10.67-11.92A132.77,132.77,0,0,0,231,128c-4.69-8.79-19.66-33.39-47.35-49.38l-18,19.75A47.71,47.71,0,0,1,176,128a8,8,0,0,1-16,0,32.09,32.09,0,0,0-25.86-31.43L128,89.79V80a8,8,0,0,1,16,0v2.05a48.1,48.1,0,0,1,10.6,2.68l-16.5-18.15A111.34,111.34,0,0,0,128,64a8,8,0,0,1,0-16c34.88,0,66.57,13.26,91.66,38.34,18.83,18.83,27.3,37.61,27.65,38.4A8,8,0,0,1,247.31,131.26Z",
    )
}
