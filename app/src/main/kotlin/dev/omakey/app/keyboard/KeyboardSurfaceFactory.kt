package dev.omakey.app.keyboard

import android.content.Context
import android.view.View

/**
 * Seam between the IME service and whichever concrete rendering strategy is in use. v1 default
 * is Compose (KeyboardComposeSurfaceFactory); a Canvas-based fallback can be swapped in behind
 * this same interface without touching OmakeyInputMethodService if profiling ever shows
 * Compose recomposition overhead causing jank in the typing hot path.
 */
fun interface KeyboardSurfaceFactory {
    fun createView(context: Context, viewModel: KeyboardViewModel): View
}
