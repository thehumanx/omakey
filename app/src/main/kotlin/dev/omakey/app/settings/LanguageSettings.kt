package dev.omakey.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.omakey.core.locale.LocalePreferences
import dev.omakey.core.locale.LocaleRegistry

/**
 * Which languages the keyboard switches between. Every available language gets a switch; with two
 * or more on, the keyboard grows a language key and shows the language on the spacebar.
 *
 * The last enabled language can't be turned off — a keyboard with no language has nothing to type
 * in, and silently re-enabling English behind the user's back would be worse than refusing.
 */
@Composable
internal fun LanguagesSection(localePreferences: LocalePreferences, localeRegistry: LocaleRegistry) {
    val settings by localePreferences.settings.collectAsState()
    val available by localeRegistry.available.collectAsState()
    available.forEach { locale ->
        val enabled = locale.id in settings.enabledIds
        val isLastEnabled = enabled && settings.enabledIds.count { id -> available.any { it.id == id } } == 1
        SettingToggle(
            title = locale.nativeName,
            description = if (isLastEnabled) {
                "${locale.displayName} — at least one language has to stay on."
            } else {
                locale.displayName
            },
            checked = enabled,
            onCheckedChange = { on ->
                if (!on && isLastEnabled) return@SettingToggle
                localePreferences.setEnabled(
                    if (on) settings.enabledIds + locale.id else settings.enabledIds - locale.id,
                )
            },
        )
    }
}
