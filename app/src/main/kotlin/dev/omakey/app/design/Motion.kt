package dev.omakey.app.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable

/*
 * Motion, following Emil Kowalski's rules for interface animation:
 * - Ease-out for anything entering or responding to a tap (a strong curve, quick start, soft
 *   landing); never ease-in, which feels sluggish at the moment the user is waiting.
 * - Short: 200–320 ms. Exits are a little faster than entries.
 * - Only transform and opacity.
 * - Direction carries meaning: a page opens from the right (the row's chevron points that way)
 *   and leaves the way it came; search rises from below, the way its field sits above the list.
 * - The screen being left moves only a quarter of the way and dims, so the eye follows the new one.
 */
object Motion {
    /** Emil's "ease-out quint"-like curve: cubic-bezier(0.23, 1, 0.32, 1). */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    const val PAGE_IN_MS = 320
    const val PAGE_OUT_MS = 260
    const val RISE_MS = 260

    fun <T> out(ms: Int): FiniteAnimationSpec<T> = tween(ms, easing = EaseOut)

    /** A page pushed on top: in from the right edge, the old one drifts left a quarter and fades. */
    fun push(): ContentTransform =
        (slideInHorizontally(out(PAGE_IN_MS)) { it } + fadeIn(out(PAGE_IN_MS / 2)))
            .togetherWith(slideOutHorizontally(out(PAGE_IN_MS)) { -it / 4 } + fadeOut(out(PAGE_IN_MS)))

    /** Back: the page slides out to the right, the one under it returns from a quarter left. */
    fun pop(): ContentTransform =
        (slideInHorizontally(out(PAGE_OUT_MS)) { -it / 4 } + fadeIn(out(PAGE_OUT_MS)))
            .togetherWith(slideOutHorizontally(out(PAGE_OUT_MS)) { it } + fadeOut(out(PAGE_OUT_MS)))

    /** Search: rises a short way from below and fades in; the home list just fades. */
    fun rise(): ContentTransform =
        (slideInVertically(out(RISE_MS)) { it / 6 } + fadeIn(out(RISE_MS)))
            .togetherWith(fadeOut(out(RISE_MS / 2)))

    /** Leaving search: it sinks back down as home fades in. */
    fun sink(): ContentTransform =
        fadeIn(out(RISE_MS)).togetherWith(slideOutVertically(out(RISE_MS)) { it / 6 } + fadeOut(out(RISE_MS / 2)))
}

/** A full-screen page shown over Settings (learned words, theme editor…): pushed from the right. */
@Composable
fun PushedPage(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(Motion.out(Motion.PAGE_IN_MS)) { it } + fadeIn(Motion.out(Motion.PAGE_IN_MS / 2)),
        exit = slideOutHorizontally(Motion.out(Motion.PAGE_OUT_MS)) { it } + fadeOut(Motion.out(Motion.PAGE_OUT_MS)),
    ) { content() }
}

/** A full-screen page that comes up from the bottom (Try omakey, opened from the bottom button). */
@Composable
fun RisingPage(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(Motion.out(Motion.PAGE_IN_MS)) { it / 4 } + fadeIn(Motion.out(Motion.PAGE_IN_MS / 2)),
        exit = slideOutVertically(Motion.out(Motion.PAGE_OUT_MS)) { it / 4 } + fadeOut(Motion.out(Motion.PAGE_OUT_MS)),
    ) { content() }
}
