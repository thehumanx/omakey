package dev.omakey.core.icons

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/*
 * omakey's own line icons, generated from the "Icons" frame of the design file (Figma "omakey",
 * Components page). A 24-unit grid, 1.8 stroke, round caps and joins: one family for Settings,
 * Quick access, the bar and the panels, so nothing falls back to a text glyph or an emoji.
 * Regenerate from the design file rather than editing paths by hand.
 */

private class P(val d: String, val stroke: Boolean, val fill: Boolean)

private fun lineIcon(vararg paths: P): ImageVector {
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    val black = SolidColor(androidx.compose.ui.graphics.Color.Black)
    paths.forEach { p ->
        b.addPath(
            pathData = PathParser().parsePathString(p.d).toNodes(),
            fill = if (p.fill) black else null,
            stroke = if (p.stroke) black else null,
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

/** Every icon by its design-file name, for tooling and tests. */
object OmakeyIcons {
    val QuickAccess: ImageVector by lazy { lineIcon(P("M5.8 4H8.7C9.69411 4 10.5 4.80589 10.5 5.8V8.7C10.5 9.69411 9.69411 10.5 8.7 10.5H5.8C4.80589 10.5 4 9.69411 4 8.7V5.8C4 4.80589 4.80589 4 5.8 4Z", true, false), P("M15.3 4H18.2C19.1941 4 20 4.80589 20 5.8V8.7C20 9.69411 19.1941 10.5 18.2 10.5H15.3C14.3059 10.5 13.5 9.69411 13.5 8.7V5.8C13.5 4.80589 14.3059 4 15.3 4Z", true, false), P("M5.8 13.5H8.7C9.69411 13.5 10.5 14.3059 10.5 15.3V18.2C10.5 19.1941 9.69411 20 8.7 20H5.8C4.80589 20 4 19.1941 4 18.2V15.3C4 14.3059 4.80589 13.5 5.8 13.5Z", true, false), P("M15.3 13.5H18.2C19.1941 13.5 20 14.3059 20 15.3V18.2C20 19.1941 19.1941 20 18.2 20H15.3C14.3059 20 13.5 19.1941 13.5 18.2V15.3C13.5 14.3059 14.3059 13.5 15.3 13.5Z", true, false)) }
    val Close: ImageVector by lazy { lineIcon(P("M6 6L18 18M18 6L6 18", true, false)) }
    val ChevronRight: ImageVector by lazy { lineIcon(P("M9 18L15 12L9 6", true, false)) }
    val Back: ImageVector by lazy { lineIcon(P("M15 18L9 12L15 6", true, false)) }
    val Plus: ImageVector by lazy { lineIcon(P("M12 5V19M5 12H19", true, false)) }
    val Lock: ImageVector by lazy { lineIcon(P("M6 11H18C19.1046 11 20 11.8954 20 13V19C20 20.1046 19.1046 21 18 21H6C4.89543 21 4 20.1046 4 19V13C4 11.8954 4.89543 11 6 11Z", true, false), P("M8 11V8C8 6.93913 8.42143 5.92172 9.17157 5.17157C9.92172 4.42143 10.9391 4 12 4C13.0609 4 14.0783 4.42143 14.8284 5.17157C15.5786 5.92172 16 6.93913 16 8V11", true, false)) }
    val Grip: ImageVector by lazy { lineIcon(P("M8.99999 7.7C9.93887 7.7 10.7 6.93888 10.7 6C10.7 5.06111 9.93887 4.3 8.99999 4.3C8.0611 4.3 7.29999 5.06111 7.29999 6C7.29999 6.93888 8.0611 7.7 8.99999 7.7Z", false, true), P("M15 7.7C15.9389 7.7 16.7 6.93888 16.7 6C16.7 5.06111 15.9389 4.3 15 4.3C14.0611 4.3 13.3 5.06111 13.3 6C13.3 6.93888 14.0611 7.7 15 7.7Z", false, true), P("M8.99999 13.7C9.93887 13.7 10.7 12.9389 10.7 12C10.7 11.0611 9.93887 10.3 8.99999 10.3C8.0611 10.3 7.29999 11.0611 7.29999 12C7.29999 12.9389 8.0611 13.7 8.99999 13.7Z", false, true), P("M15 13.7C15.9389 13.7 16.7 12.9389 16.7 12C16.7 11.0611 15.9389 10.3 15 10.3C14.0611 10.3 13.3 11.0611 13.3 12C13.3 12.9389 14.0611 13.7 15 13.7Z", false, true), P("M8.99999 19.7C9.93887 19.7 10.7 18.9389 10.7 18C10.7 17.0611 9.93887 16.3 8.99999 16.3C8.0611 16.3 7.29999 17.0611 7.29999 18C7.29999 18.9389 8.0611 19.7 8.99999 19.7Z", false, true), P("M15 19.7C15.9389 19.7 16.7 18.9389 16.7 18C16.7 17.0611 15.9389 16.3 15 16.3C14.0611 16.3 13.3 17.0611 13.3 18C13.3 18.9389 14.0611 19.7 15 19.7Z", false, true)) }
    val Shift: ImageVector by lazy { lineIcon(P("M12 4L20 12H15.5V20H8.5V12H4L12 4Z", true, false)) }
    val Backspace: ImageVector by lazy { lineIcon(P("M21 5H9L3 12L9 19H21V5Z", true, false), P("M17 9L12 15M12 9L17 15", true, false)) }
    val Emoji: ImageVector by lazy { lineIcon(P("M21 12C21 16.9706 16.9706 21 12 21C7.02944 21 3 16.9706 3 12C3 7.02944 7.02944 3 12 3C16.9706 3 21 7.02944 21 12Z", true, false), P("M8.5 14.5C8.92183 15.022 9.45506 15.443 10.0607 15.7322C10.6663 16.0215 11.3289 16.1716 12 16.1716C12.6711 16.1716 13.3337 16.0215 13.9393 15.7322C14.5449 15.443 15.0782 15.022 15.5 14.5", true, false), P("M9 9.5H9.01M15 9.5H15.01", true, false)) }
    val ArrowRight: ImageVector by lazy { lineIcon(P("M5 12H19M13 18L19 12L13 6", true, false)) }
    val Globe: ImageVector by lazy { lineIcon(P("M21 12C21 16.9706 16.9706 21 12 21C7.02944 21 3 16.9706 3 12C3 7.02944 7.02944 3 12 3C16.9706 3 21 7.02944 21 12Z", true, false), P("M3 12H21M12 3C14.1162 5.52153 15.2762 8.70813 15.2762 12C15.2762 15.2919 14.1162 18.4785 12 21C9.8838 18.4785 8.72381 15.2919 8.72381 12C8.72381 8.70813 9.8838 5.52153 12 3Z", true, false)) }
    val Settings: ImageVector by lazy { lineIcon(P("M4 7H14M18 7H20M4 17H8M12 17H20", true, false), P("M18 7C18 8.10457 17.1046 9 16 9C14.8954 9 14 8.10457 14 7C14 5.89543 14.8954 5 16 5C17.1046 5 18 5.89543 18 7Z", true, false), P("M12 17C12 18.1046 11.1046 19 10 19C8.89543 19 8 18.1046 8 17C8 15.8954 8.89543 15 10 15C11.1046 15 12 15.8954 12 17Z", true, false)) }
    val Keyboard: ImageVector by lazy { lineIcon(P("M6 6H18C19.6569 6 21 7.34315 21 9V15C21 16.6569 19.6569 18 18 18H6C4.34315 18 3 16.6569 3 15V9C3 7.34315 4.34315 6 6 6Z", true, false), P("M7 10H7.01M11 10H11.01M15 10H15.01M8 14H16", true, false)) }
    val Snippet: ImageVector by lazy { lineIcon(P("M8 7H16C18.7614 7 21 9.23858 21 12C21 14.7614 18.7614 17 16 17H8C5.23858 17 3 14.7614 3 12C3 9.23858 5.23858 7 8 7Z", true, false), P("M8 12H16", true, false)) }
    val Split: ImageVector by lazy { lineIcon(P("M5 7H8.5C9.60457 7 10.5 7.89543 10.5 9V15C10.5 16.1046 9.60457 17 8.5 17H5C3.89543 17 3 16.1046 3 15V9C3 7.89543 3.89543 7 5 7Z", true, false), P("M15.5 7H19C20.1046 7 21 7.89543 21 9V15C21 16.1046 20.1046 17 19 17H15.5C14.3954 17 13.5 16.1046 13.5 15V9C13.5 7.89543 14.3954 7 15.5 7Z", true, false)) }
    val Compact: ImageVector by lazy { lineIcon(P("M5.8 5H18.2C19.1941 5 20 5.80589 20 6.8V8.2C20 9.19411 19.1941 10 18.2 10H5.8C4.80589 10 4 9.19411 4 8.2V6.8C4 5.80589 4.80589 5 5.8 5Z", true, false), P("M5.8 14H18.2C19.1941 14 20 14.8059 20 15.8V17.2C20 18.1941 19.1941 19 18.2 19H5.8C4.80589 19 4 18.1941 4 17.2V15.8C4 14.8059 4.80589 14 5.8 14Z", true, false)) }
    val Floating: ImageVector by lazy { lineIcon(P("M6.5 8H17.5C18.8807 8 20 9.11929 20 10.5V15.5C20 16.8807 18.8807 18 17.5 18H6.5C5.11929 18 4 16.8807 4 15.5V10.5C4 9.11929 5.11929 8 6.5 8Z", true, false), P("M10 4.5H14", true, false)) }
    val OneHanded: ImageVector by lazy { lineIcon(P("M11.5 5H17.5C18.8807 5 20 6.11929 20 7.5V16.5C20 17.8807 18.8807 19 17.5 19H11.5C10.1193 19 9 17.8807 9 16.5V7.5C9 6.11929 10.1193 5 11.5 5Z", true, false), P("M5 9V15", true, false)) }
    val Incognito: ImageVector by lazy { lineIcon(P("M3 12C3 12 6.5 6 12 6C17.5 6 21 12 21 12C21 12 17.5 18 12 18C6.5 18 3 12 3 12Z", true, false), P("M4 20L20 4", true, false)) }
    val Resize: ImageVector by lazy { lineIcon(P("M4 9V4H9M20 9V4H15M4 15V20H9M20 15V20H15", true, false)) }
    val Theme: ImageVector by lazy { lineIcon(P("M20.5 12C20.5 16.6944 16.6944 20.5 12 20.5C7.30558 20.5 3.5 16.6944 3.5 12C3.5 7.30558 7.30558 3.5 12 3.5C16.6944 3.5 20.5 7.30558 20.5 12Z", true, false), P("M12 3.5C9.74566 3.5 7.58365 4.39553 5.98959 5.98959C4.39553 7.58365 3.5 9.74566 3.5 12C3.5 14.2543 4.39553 16.4163 5.98959 18.0104C7.58365 19.6045 9.74566 20.5 12 20.5V3.5Z", true, true)) }
    val Search: ImageVector by lazy { lineIcon(P("M17.5 11C17.5 14.5899 14.5899 17.5 11 17.5C7.41015 17.5 4.5 14.5899 4.5 11C4.5 7.41015 7.41015 4.5 11 4.5C14.5899 4.5 17.5 7.41015 17.5 11Z", true, false), P("M20 20L15.8 15.8", true, false)) }
    val Copy: ImageVector by lazy { lineIcon(P("M10.5 8H17.5C18.8807 8 20 9.11929 20 10.5V17.5C20 18.8807 18.8807 20 17.5 20H10.5C9.11929 20 8 18.8807 8 17.5V10.5C8 9.11929 9.11929 8 10.5 8Z", true, false), P("M16 8V6C16 5.46957 15.7893 4.96086 15.4142 4.58579C15.0391 4.21071 14.5304 4 14 4H6C5.46957 4 4.96086 4.21071 4.58579 4.58579C4.21071 4.96086 4 5.46957 4 6V14C4 14.5304 4.21071 15.0391 4.58579 15.4142C4.96086 15.7893 5.46957 16 6 16H8", true, false)) }
    val Clipboard: ImageVector by lazy { lineIcon(P("M9 3H15C15.5523 3 16 3.44772 16 4V6C16 6.55228 15.5523 7 15 7H9C8.44772 7 8 6.55228 8 6V4C8 3.44772 8.44772 3 9 3Z", true, false), P("M16 5H18C18.5304 5 19.0391 5.21071 19.4142 5.58579C19.7893 5.96086 20 6.46957 20 7V19C20 19.5304 19.7893 20.0391 19.4142 20.4142C19.0391 20.7893 18.5304 21 18 21H6C5.46957 21 4.96086 20.7893 4.58579 20.4142C4.21071 20.0391 4 19.5304 4 19V7C4 6.46957 4.21071 5.96086 4.58579 5.58579C4.96086 5.21071 5.46957 5 6 5H8", true, false)) }
    val Pin: ImageVector by lazy { lineIcon(P("M9 4H15L14 10L17 13H7L10 10L9 4Z", true, false), P("M12 13V20", true, false)) }
    val Trash: ImageVector by lazy { lineIcon(P("M4 7H20M10 11V17M14 11V17M6 7L7 20H17L18 7M9 7V4H15V7", true, false)) }
    val Check: ImageVector by lazy { lineIcon(P("M5 12.5L9.5 17L19 7.5", true, false)) }
    val SelectAll: ImageVector by lazy { lineIcon(P("M5.85164 4.22764C6.20536 4.08096 6.59323 4 7 4H8.25M4.22764 5.85164C4.08096 6.20536 4 6.59323 4 7V8.25M4 10.75V13.25M4 15.75V17C4 17.4068 4.08096 17.7946 4.22764 18.1484M5.85164 19.7724C6.20536 19.919 6.59323 20 7 20H8.25M10.75 20H13.25M15.75 20H17C17.4068 20 17.7946 19.919 18.1484 19.7724M19.7724 18.1484C19.919 17.7946 20 17.4068 20 17V15.75M20 13.25V10.75M20 8.25V7C20 6.59323 19.919 6.20536 19.7724 5.85164M18.1484 4.22764C17.7946 4.08096 17.4068 4 17 4H15.75M13.25 4H10.75", true, false), P("M8.5 12L11 14.5L15.5 9.5", true, false)) }
    val Undo: ImageVector by lazy { lineIcon(P("M9 14L5 10L9 6", true, false), P("M5 10H14C15.3261 10 16.5979 10.5268 17.5355 11.4645C18.4732 12.4021 19 13.6739 19 15C19 16.3261 18.4732 17.5979 17.5355 18.5355C16.5979 19.4732 15.3261 20 14 20H12", true, false)) }
    val Mic: ImageVector by lazy { lineIcon(P("M12 3C13.6569 3 15 4.34315 15 6V11C15 12.6569 13.6569 14 12 14C10.3431 14 9 12.6569 9 11V6C9 4.34315 10.3431 3 12 3Z", true, false), P("M6 11C6 12.5913 6.63214 14.1174 7.75736 15.2426C8.88258 16.3679 10.4087 17 12 17M12 17C13.5913 17 15.1174 16.3679 16.2426 15.2426C17.3679 14.1174 18 12.5913 18 11M12 17V21", true, false)) }
    val Download: ImageVector by lazy { lineIcon(P("M12 4V15M17 10L12 15L7 10M5 20H19", true, false)) }
    val Calendar: ImageVector by lazy { lineIcon(P("M6.5 5H17.5C18.8807 5 20 6.11929 20 7.5V17.5C20 18.8807 18.8807 20 17.5 20H6.5C5.11929 20 4 18.8807 4 17.5V7.5C4 6.11929 5.11929 5 6.5 5Z", true, false), P("M4 10H20M9 3V7M15 3V7", true, false)) }
    val Clock: ImageVector by lazy { lineIcon(P("M20.5 12C20.5 16.6944 16.6944 20.5 12 20.5C7.30558 20.5 3.5 16.6944 3.5 12C3.5 7.30558 7.30558 3.5 12 3.5C16.6944 3.5 20.5 7.30558 20.5 12Z", true, false), P("M12 7.5V12L15 14", true, false)) }
    val More: ImageVector by lazy { lineIcon(P("M12 7.1C12.8837 7.1 13.6 6.38366 13.6 5.5C13.6 4.61635 12.8837 3.9 12 3.9C11.1164 3.9 10.4 4.61635 10.4 5.5C10.4 6.38366 11.1164 7.1 12 7.1Z", false, true), P("M12 13.6C12.8837 13.6 13.6 12.8837 13.6 12C13.6 11.1163 12.8837 10.4 12 10.4C11.1164 10.4 10.4 11.1163 10.4 12C10.4 12.8837 11.1164 13.6 12 13.6Z", false, true), P("M12 20.1C12.8837 20.1 13.6 19.3837 13.6 18.5C13.6 17.6163 12.8837 16.9 12 16.9C11.1164 16.9 10.4 17.6163 10.4 18.5C10.4 19.3837 11.1164 20.1 12 20.1Z", false, true)) }
    val Book: ImageVector by lazy { lineIcon(P("M5 4H15C16.0609 4 17.0783 4.42143 17.8284 5.17157C18.5786 5.92172 19 6.93913 19 8V20H9C7.93913 20 6.92172 19.5786 6.17157 18.8284C5.42143 18.0783 5 17.0609 5 16V4Z", true, false), P("M5 16C5 14.9391 5.42143 13.9217 6.17157 13.1716C6.92172 12.4214 7.93913 12 9 12H19", true, false)) }
    val Shield: ImageVector by lazy { lineIcon(P("M12 3L20 6V12C20 17 16.5 20 12 21C7.5 20 4 17 4 12V6L12 3Z", true, false)) }
    val Sound: ImageVector by lazy { lineIcon(P("M4 9V15H8L13 19V5L8 9H4Z", true, false), P("M16.5 9C16.9256 9.37537 17.2665 9.83701 17.5 10.3542C17.7335 10.8715 17.8542 11.4325 17.8542 12C17.8542 12.5675 17.7335 13.1285 17.5 13.6458C17.2665 14.163 16.9256 14.6246 16.5 15", true, false)) }
    val Hand: ImageVector by lazy { lineIcon(P("M7.99998 13V5.5C7.99998 5.10218 8.15802 4.72064 8.43932 4.43934C8.72063 4.15804 9.10216 4 9.49998 4C9.89781 4 10.2793 4.15804 10.5606 4.43934C10.8419 4.72064 11 5.10218 11 5.5V11M11 10.5V4.5C11 4.10218 11.158 3.72064 11.4393 3.43934C11.7206 3.15804 12.1022 3 12.5 3C12.8978 3 13.2793 3.15804 13.5606 3.43934C13.8419 3.72064 14 4.10218 14 4.5V11V6.5C14 6.10218 14.158 5.72064 14.4393 5.43934C14.7206 5.15804 15.1022 5 15.5 5C15.8978 5 16.2793 5.15804 16.5606 5.43934C16.8419 5.72064 17 6.10218 17 6.5V14C17 15.8565 16.2625 17.637 14.9497 18.9497C13.637 20.2625 11.8565 21 9.99998 21C8.98025 20.9663 7.98594 20.6732 7.11105 20.1482C6.23615 19.6233 5.50957 18.8839 4.99998 18L2.99998 14C2.89979 13.6789 2.91041 13.3335 3.03014 13.0192C3.14986 12.7049 3.37174 12.44 3.66014 12.2669C3.94854 12.0939 4.28673 12.0228 4.6204 12.0651C4.95406 12.1073 5.26384 12.2605 5.49998 12.5L7.99998 15", true, false)) }
    val Type: ImageVector by lazy { lineIcon(P("M5 6H19M12 6V19M9 19H15", true, false)) }
    val Accessibility: ImageVector by lazy { lineIcon(P("M13.5 4.5C13.5 5.32843 12.8284 6 12 6C11.1716 6 10.5 5.32843 10.5 4.5C10.5 3.67157 11.1716 3 12 3C12.8284 3 13.5 3.67157 13.5 4.5Z", true, false), P("M5 8.5L12 10M12 10L19 8.5M12 10V14M12 14L9 20M12 14L15 20", true, false)) }
    val Info: ImageVector by lazy { lineIcon(P("M21 12C21 16.9706 16.9706 21 12 21C7.02944 21 3 16.9706 3 12C3 7.02944 7.02944 3 12 3C16.9706 3 21 7.02944 21 12Z", true, false), P("M12 11V16M12 8H12.01", true, false)) }
    val Paw: ImageVector by lazy { lineIcon(P("M8.80001 10C8.80001 10.9941 7.99412 11.8 7.00001 11.8C6.0059 11.8 5.20001 10.9941 5.20001 10C5.20001 9.00588 6.0059 8.2 7.00001 8.2C7.99412 8.2 8.80001 9.00588 8.80001 10Z", true, false), P("M12.8 6.5C12.8 7.49411 11.9941 8.3 11 8.3C10.0059 8.3 9.20001 7.49411 9.20001 6.5C9.20001 5.50588 10.0059 4.7 11 4.7C11.9941 4.7 12.8 5.50588 12.8 6.5Z", true, false), P("M17.8 7.5C17.8 8.49411 16.9941 9.3 16 9.3C15.0059 9.3 14.2 8.49411 14.2 7.5C14.2 6.50588 15.0059 5.7 16 5.7C16.9941 5.7 17.8 6.50588 17.8 7.5Z", true, false), P("M20.1 12C20.1 12.8837 19.3837 13.6 18.5 13.6C17.6164 13.6 16.9 12.8837 16.9 12C16.9 11.1163 17.6164 10.4 18.5 10.4C19.3837 10.4 20.1 11.1163 20.1 12Z", true, false), P("M8.5 17.5C8.5 14.5 10.5 12.5 13 12.5C15.5 12.5 17 14.5 17 16.5C17 18.5 15.5 19.5 14 19.5C12.8 19.5 12.2 18.9 11 18.9C9.8 18.9 8.5 19.8 8.5 17.5Z", true, false)) }
    val Coffee: ImageVector by lazy { lineIcon(P("M5 9H16V14C16 15.3261 15.4732 16.5979 14.5355 17.5355C13.5979 18.4732 12.3261 19 11 19H10C8.67392 19 7.40215 18.4732 6.46447 17.5355C5.52678 16.5979 5 15.3261 5 14V9Z", true, false), P("M16 10H17.5C18.163 10 18.7989 10.2634 19.2678 10.7322C19.7366 11.2011 20 11.837 20 12.5C20 13.163 19.7366 13.7989 19.2678 14.2678C18.7989 14.7366 18.163 15 17.5 15H16M9 3V6M12.5 3V6", true, false)) }
    val Plane: ImageVector by lazy { lineIcon(P("M3 13L10 11L14 4L16 5L14 11L20 10L21 12L15 15L16 21L14 22L11 16L5 17L3 13Z", true, false)) }
    val Ball: ImageVector by lazy { lineIcon(P("M20.5 12C20.5 16.6944 16.6944 20.5 12 20.5C7.30558 20.5 3.5 16.6944 3.5 12C3.5 7.30558 7.30558 3.5 12 3.5C16.6944 3.5 20.5 7.30558 20.5 12Z", true, false), P("M12 7.5L16 10.5L14.5 15H9.5L8 10.5L12 7.5Z", true, false), P("M12 3.5V7.5M20.2 10L16 10.5M17 19L14.5 15M7 19L9.5 15M3.8 10L8 10.5", true, false)) }
    val Bulb: ImageVector by lazy { lineIcon(P("M9 18H15M10 21H14", true, false), P("M12 3C10.723 2.99202 9.47675 3.39169 8.44257 4.14086C7.40838 4.89004 6.6402 5.94964 6.24978 7.16553C5.85936 8.38141 5.86706 9.69015 6.27176 10.9014C6.67647 12.1126 7.45707 13.1631 8.5 13.9C9.1 14.4 9.5 15.1 9.5 16H14.5C14.5 15.1 14.9 14.4 15.5 13.9C16.5429 13.1631 17.3235 12.1126 17.7282 10.9014C18.1329 9.69015 18.1406 8.38141 17.7502 7.16553C17.3598 5.94964 16.5916 4.89004 15.5574 4.14086C14.5233 3.39169 13.277 2.99202 12 3Z", true, false)) }
    val Heart: ImageVector by lazy { lineIcon(P("M12 20C12 20 4.5 15.4 4.5 10C4.56399 9.16139 4.86172 8.35751 5.3594 7.67952C5.85709 7.00154 6.53481 6.4766 7.3157 6.16424C8.09659 5.85189 8.94938 5.76462 9.77735 5.91234C10.6053 6.06007 11.3753 6.43686 12 7C12.6247 6.43686 13.3947 6.06007 14.2227 5.91234C15.0506 5.76462 15.9034 5.85189 16.6843 6.16424C17.4652 6.4766 18.1429 7.00154 18.6406 7.67952C19.1383 8.35751 19.436 9.16139 19.5 10C19.5 15.4 12 20 12 20Z", true, false)) }
    val Flag: ImageVector by lazy { lineIcon(P("M5 21V4H16L14 8L16 12H5", true, false)) }
    val Hash: ImageVector by lazy { lineIcon(P("M9 4L7 20M17 4L15 20M4 9H20M3 15H19", true, false)) }
    val SpaceBar: ImageVector by lazy { lineIcon(P("M4 10V14H20V10", true, false)) }

    // Drawn for the keyboard, in the same grid and stroke as the design file's icons.
    val ShiftLocked: ImageVector by lazy { lineIcon(P("M12 3L19.5 10.5H15.5V16H8.5V10.5H4.5L12 3Z", true, false), P("M8.5 20H15.5", true, false)) }
    val Enter: ImageVector by lazy { lineIcon(P("M19 5V11C19 12.1046 18.1046 13 17 13H5", true, false), P("M9 9L5 13L9 17", true, false)) }
    val Send: ImageVector by lazy { lineIcon(P("M4.5 11.5L20 4L13 19.5L11 13L4.5 11.5Z", true, false), P("M11 13L20 4", true, false)) }
    val Cut: ImageVector by lazy { lineIcon(P("M9.5 17C9.5 18.3807 8.38071 19.5 7 19.5C5.61929 19.5 4.5 18.3807 4.5 17C4.5 15.6193 5.61929 14.5 7 14.5C8.38071 14.5 9.5 15.6193 9.5 17Z", true, false), P("M19.5 17C19.5 18.3807 18.3807 19.5 17 19.5C15.6193 19.5 14.5 18.3807 14.5 17C14.5 15.6193 15.6193 14.5 17 14.5C18.3807 14.5 19.5 15.6193 19.5 17Z", true, false), P("M8.8 15.2L17 4M15.2 15.2L7 4", true, false)) }
    val Paste: ImageVector by lazy { lineIcon(P("M9 3H15C15.5523 3 16 3.44772 16 4V6C16 6.55228 15.5523 7 15 7H9C8.44772 7 8 6.55228 8 6V4C8 3.44772 8.44772 3 9 3Z", true, false), P("M16 5H18C19.1046 5 20 5.89543 20 7V19C20 20.1046 19.1046 21 18 21H6C4.89543 21 4 20.1046 4 19V7C4 5.89543 4.89543 5 6 5H8", true, false), P("M9 12H15M9 16H13", true, false)) }
    val Redo: ImageVector by lazy { lineIcon(P("M15 14L19 10L15 6", true, false), P("M19 10H10C8.67392 10 7.40215 10.5268 6.46447 11.4645C5.52678 12.4021 5 13.6739 5 15C5 16.3261 5.52678 17.5979 6.46447 18.5355C7.40215 19.4732 8.67392 20 10 20H12", true, false)) }
    val ArrowLeft: ImageVector by lazy { lineIcon(P("M19 12H5M11 18L5 12L11 6", true, false)) }
    val LayoutGrid: ImageVector by lazy { lineIcon(P("M6 4H18C19.1046 4 20 4.89543 20 6V18C20 19.1046 19.1046 20 18 20H6C4.89543 20 4 19.1046 4 18V6C4 4.89543 4.89543 4 6 4Z", true, false), P("M4 9.5H20M4 14.5H20M9.5 4V20M14.5 4V20", true, false)) }
    val LayoutRows: ImageVector by lazy { lineIcon(P("M5 7H19M5 12H19M8 17H16", true, false)) }
    val SwitchSide: ImageVector by lazy { lineIcon(P("M7 8L3 12L7 16M17 8L21 12L17 16M3 12H21", true, false)) }
    val Expand: ImageVector by lazy { lineIcon(P("M14 4H20V10M10 20H4V14M20 4L13.5 10.5M4 20L10.5 13.5", true, false)) }

    val names: List<String> = listOf("quick-access", "close", "chevron-right", "back", "plus", "lock", "grip", "shift", "backspace", "emoji", "arrow-right", "globe", "settings", "keyboard", "snippet", "split", "compact", "floating", "one-handed", "incognito", "resize", "theme", "search", "copy", "clipboard", "pin", "trash", "check", "select-all", "undo", "mic", "download", "calendar", "clock", "more", "book", "shield", "sound", "hand", "type", "accessibility", "info", "paw", "coffee", "plane", "ball", "bulb", "heart", "flag", "hash", "space-bar")
}
