package dev.omakey.core.theme

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

/** The device's Material You dynamic accent color (Android 12+ only) — reads the actual system
 * palette resource (`system_accent1_500`) rather than approximating one from wallpaper colors
 * ourselves, so this genuinely matches what the user already sees as "the accent color" elsewhere
 * in the OS. Returns null pre-Android-12, or if the resource can't be resolved for any reason
 * (some OEM skins omit or override these ids) — callers fall back to the theme's own color. */
fun systemAccentColor(context: Context): ColorSpec? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        ColorSpec.fromArgbInt(ContextCompat.getColor(context, android.R.color.system_accent1_500))
    }.getOrNull()
}
