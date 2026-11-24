package dev.omakey.core.icons

import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The icon names the keyboard and Settings were written against, now pointing at omakey's own
 * line icons ([OmakeyIcons], drawn in the design file): one family everywhere, at one stroke.
 * The filled Phosphor paths these used to hold are gone; new code uses OmakeyIcons directly.
 */

val PhosphorLineArrowLeft: ImageVector get() = OmakeyIcons.ArrowLeft
val PhosphorLineMagnifyingGlass: ImageVector get() = OmakeyIcons.Search
val PhosphorLineX: ImageVector get() = OmakeyIcons.Close
val PhosphorLineCaretRight: ImageVector get() = OmakeyIcons.ChevronRight
val PhosphorLineKeyboard: ImageVector get() = OmakeyIcons.Keyboard
val PhosphorLineWarningCircle: ImageVector get() = OmakeyIcons.Info
val PhosphorLineCheckCircle: ImageVector get() = OmakeyIcons.Check
val PhosphorLineCheck: ImageVector get() = OmakeyIcons.Check
val PhosphorLineCopy: ImageVector get() = OmakeyIcons.Copy
