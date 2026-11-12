package dev.omakey.app.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The setting a search result just opened, flashed for a moment so the eye finds it; null
 * otherwise. Provided by SettingsScreen. */
internal val LocalSettingHighlight = compositionLocalOf<SettingKey?> { null }

/** A modifier for the row of [key]: paints the flash behind it and scrolls it fully into view
 * while it's the one being highlighted. The page has already scrolled its section into view;
 * this finishes the job for rows deep inside a long section. Composable (it holds an animation
 * and a requester), so it's a `remember…` function returning a plain Modifier rather than a
 * composable modifier factory. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSettingHighlight(key: SettingKey): Modifier {
    val highlighted = LocalSettingHighlight.current == key
    val color by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 400),
        label = "settingHighlight",
    )
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            // Let the page's own scroll to the section land first; two scrolls at once fight.
            delay(350)
            requester.bringIntoView()
        }
    }
    return Modifier
        .bringIntoViewRequester(requester)
        .background(color, RoundedCornerShape(8.dp))
}
