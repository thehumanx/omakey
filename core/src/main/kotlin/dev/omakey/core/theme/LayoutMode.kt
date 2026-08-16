package dev.omakey.core.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** Visual *structure* of the keyboard, orthogonal to color ([OmakeyTheme]/[Presets]) — every
 * color theme (Light/Dark/Follow system/Accent/custom) can be paired with either layout mode.
 * [NORMAL] is the original look (rounded/pill keys, gaps between them, no grid lines). [GRID]
 * renders every key as a bordered, edge-to-edge rectangular cell with no gaps — a plain
 * spreadsheet-like grid — and fills a pressed key's whole cell solid instead of just tinting its
 * icon/text, using colors already defined on the active [OmakeyTheme] ([OmakeyTheme.keyBackground]/
 * [OmakeyTheme.keyBackgroundPressed]/[OmakeyTheme.keyTextColor]) rather than any new hardcoded
 * palette. */
enum class LayoutMode { NORMAL, GRID }

val LocalKeyboardLayoutMode = staticCompositionLocalOf { LayoutMode.NORMAL }
