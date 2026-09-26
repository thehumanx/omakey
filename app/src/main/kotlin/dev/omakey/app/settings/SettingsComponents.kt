package dev.omakey.app.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.keyboard.SoundCatalog
import dev.omakey.app.keyboard.VibratorKeyboardFeedback
import dev.omakey.app.keyboard.ui.FontCatalog
import dev.omakey.core.emoji.EmojiSkinTone
import dev.omakey.core.emoji.EmojiSkinTonePreferences
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.feedback.HapticSoundSettings
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.AutocorrectSettings
import dev.omakey.core.predict.IncognitoPreferences
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.theme.AccessibilityPreferences
import dev.omakey.core.theme.FontPreferences
import kotlinx.coroutines.launch

/*
 * The reusable pieces of the settings list: section headers, the shared toggle and clickable rows,
 * the individual setting toggles, and the pickers. Split out of SettingsActivity.kt.
 */

/** Groups related settings into a labeled, visually distinct card instead of a flat list of raw
 * text headers — the previous layout put every section at the same visual weight, which read as
 * "everything is one long list" rather than a set of related groups. */
@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

/** Best-effort check via the flattened default-IME component name in Settings.Secure — good
 * enough to decide whether to show the "switch keyboard" nudge, not used for anything security
 * sensitive. */
internal fun isOmakeyDefaultIme(context: android.content.Context): Boolean {
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return current?.contains(context.packageName) == true
}

/** Whether omakey is enabled at all as an available input method — distinct from
 * [isOmakeyDefaultIme] (enabled but not necessarily the one currently selected). Same
 * best-effort-only caveat. */
private fun isOmakeyEnabled(context: android.content.Context): Boolean {
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
        as android.view.inputmethod.InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

/** Re-checked on every `ON_RESUME` (not just once) — the whole point of these two checks is to
 * reflect whatever just happened in the system Settings screen the two Setup buttons send the
 * user to, and that screen is a separate Activity this one resumes underneath when they come
 * back. */
@Composable
internal fun rememberSetupStatus(): Pair<Boolean, Boolean> {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(isOmakeyEnabled(context)) }
    var isDefault by remember { mutableStateOf(isOmakeyDefaultIme(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isEnabled = isOmakeyEnabled(context)
                isDefault = isOmakeyDefaultIme(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return isEnabled to isDefault
}

/** A checkmark once the corresponding Setup step is done, an exclamation mark while it still
 * needs attention — quick at-a-glance status instead of having to tap each button to find out. */
@Composable
internal fun SetupStatusIcon(done: Boolean) {
    if (done) {
        Icon(
            imageVector = dev.omakey.core.icons.PhosphorCheckmark,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color(0xFF3A9D5D),
            modifier = Modifier.size(20.dp),
        )
    } else {
        Text(
            text = "!",
            color = androidx.compose.ui.graphics.Color(0xFFC77B00),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun FontPicker(fontPreferences: FontPreferences, currentFontId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FontCatalog.displayNames.forEach { (id, name) ->
            val selected = id == currentFontId
            val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { fontPreferences.setFont(id) }
                    .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Ag",
                    fontFamily = FontCatalog.resolve(id),
                    fontSize = 22.sp,
                    modifier = Modifier.width(40.dp),
                )
                Text(text = name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The six Fitzpatrick tones, each drawn as the actual toned emoji rather than a colour swatch.
 *
 * A flat colour chip would be a guess at what the font renders — tones vary between emoji fonts,
 * and the point of the setting is what lands in the message. Showing the real glyph means the
 * preview cannot disagree with the result. "Default" is the unmodified yellow, which is an absence
 * of a modifier rather than a sixth tone, and reads correctly as such when shown alongside.
 */
@Composable
internal fun EmojiSkinTonePicker(preferences: EmojiSkinTonePreferences) {
    val current by preferences.skinTone.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Emoji skin tone", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Applied to emoji that support it — hands, faces and people. Everything else " +
                "is unaffected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (tone in EmojiSkinTone.ALL) {
                val selected = tone == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { preferences.setSkinTone(tone) }
                        .semantics { contentDescription = tone.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = tone.sample, fontSize = 22.sp)
                }
            }
        }
        Text(
            text = current.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AutocorrectToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        title = "Autocorrect",
        description = "Automatically fixes likely typos the moment you finish a word, without " +
            "asking. Backspacing right after reverts to what you actually typed. Turning this " +
            "off only disables the silent auto-fix — correction suggestions in the strip " +
            "(swipe or tap to accept) stay available either way.",
        checked = settings.autocorrectEnabled,
        onCheckedChange = autocorrectPreferences::setAutocorrectEnabled,
    )
}

@Composable
internal fun AutoCapitalizeToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        title = "Auto-capitalize",
        description = "Capitalizes the first letter of a new field and after sentence-ending " +
            "punctuation (. ! ?). Off by default.",
        checked = settings.autoCapitalizeEnabled,
        onCheckedChange = autocorrectPreferences::setAutoCapitalizeEnabled,
    )
}

@Composable
internal fun DoubleTapSpaceForPeriodToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        title = "Double-tap space for period",
        description = "Tap (or swipe right, if that's enabled) space twice quickly to insert " +
            "a period instead of two spaces. Off by default.",
        checked = settings.doubleTapSpaceForPeriod,
        onCheckedChange = autocorrectPreferences::setDoubleTapSpaceForPeriod,
    )
}

@Composable
internal fun NextWordPredictionToggle(predictionPreferences: PredictionPreferences) {
    val settings by predictionPreferences.settings.collectAsState()
    SettingToggle(
        title = "Next-word prediction",
        description = "Guesses what word comes next based on common usage and your own typing " +
            "history, shown in the suggestion strip once you finish a word. Turn off to only " +
            "ever see a suggestion there when there's an actual correction to offer.",
        checked = settings.nextWordPredictionEnabled,
        onCheckedChange = predictionPreferences::setNextWordPredictionEnabled,
    )
}

@Composable
internal fun ImplicitLearningToggle(incognitoPreferences: IncognitoPreferences) {
    val settings by incognitoPreferences.settings.collectAsState()
    SettingToggle(
        title = "Learn from my typing",
        description = "Remembers names, slang and jargon you type so they stop being flagged as " +
            "typos and start showing up as suggestions. A word has to be typed a few times before " +
            "it's treated as real, so an occasional slip doesn't get learned. Everything stays on " +
            "this device. Password fields are never learned from, and the incognito button in the " +
            "keyboard's tools row pauses it for anything else.",
        checked = settings.implicitLearningEnabled,
        onCheckedChange = incognitoPreferences::setImplicitLearningEnabled,
    )
}

/** "Key backgrounds" (renamed from "Boxed keys" — the old name read as a reference to Grid mode,
 * which this toggle has nothing to do with; Grid mode always shows bordered cells regardless of
 * this setting) and "Home row highlight" — both purely visual, so they live in Appearance now
 * rather than Typing. */
/** The enlarged-character bubble above a tapped key. Lives in Typing, not Appearance: it is
 * feedback about what you just typed, and it appears only while typing — grouping it with static
 * looks (themes, fonts, key shapes) put it where nobody would look for it. Distinct from
 * `GestureSettings.showKeyPopup`, the long-press accent popup, which is in Gestures. */
@Composable
internal fun TapPreviewToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        title = "Show key press popup",
        description = "Briefly shows an enlarged copy of the letter above your finger on " +
            "every tap. Distinct from \"Long press for special characters\" — this one is " +
            "the ordinary per-tap preview, not the held-key accent picker.",
        checked = settings.showTapPreview,
        onCheckedChange = layoutPreferences::setShowTapPreview,
    )
}

/** Blank gutters down both sides of the keyboard. Fleksy shipped the same option for the same
 * reason — on a phone with no side bezel the outer keys sit where the glass curves away, which is
 * exactly where a thumb slides off. */
@Composable
internal fun SwapEmojiLanguageToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        title = "Swap emoji and language buttons",
        description = "Normally the language button sits at the right of the suggestion bar and " +
            "the emoji key next to the spacebar. Turn on to put the emoji button up top and the " +
            "language key by the spacebar. Only applies with two or more languages.",
        checked = settings.swapEmojiAndLanguage,
        onCheckedChange = layoutPreferences::setSwapEmojiAndLanguage,
    )
}

@Composable
internal fun EdgePaddingToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        title = "Add padding",
        description = "Leave a gap down the left and right edges, so the outer keys aren't flush " +
            "against a curved or bezel-less screen edge. Costs a little key width.",
        checked = settings.edgePadding,
        onCheckedChange = layoutPreferences::setEdgePadding,
    )
}

@Composable
internal fun AppearanceLayoutToggles(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingToggle(
            title = "Key backgrounds",
            description = "Shows a background box behind every key, instead of the flat default. " +
                "Normal mode only — Grid mode always shows bordered keys.",
            checked = settings.showKeyBackgrounds,
            onCheckedChange = layoutPreferences::setShowKeyBackgrounds,
        )
        SettingToggle(
            title = "Home row highlight",
            description = "A light stripe behind the ASDF row to help find it by feel.",
            checked = settings.showMiddleRowStripe,
            onCheckedChange = layoutPreferences::setShowMiddleRowStripe,
        )
    }
}

@Composable
internal fun CapitalizationToggleSection(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        title = "Always show capital letters",
        description = "Keycaps always show uppercase letters, regardless of shift state " +
            "(omakey's default look). Turn off for the usual keyboard behavior — lowercase " +
            "keycaps that switch to uppercase only while shift is on.",
        checked = settings.alwaysShowUppercaseLetters,
        onCheckedChange = layoutPreferences::setAlwaysShowUppercaseLetters,
    )
}

@Composable
internal fun SettingToggle(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A settings row that opens something (an overlay, a picker) — the whole row is the tap target,
 * not just a trailing button, per standard Android settings-list UX (Settings app itself, most
 * system preference screens). The trailing "❯" is a plain visual affordance, not itself
 * interactive — it doesn't need to be, since the whole row already is. */
@Composable
internal fun ClickableSettingRow(title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = "❯",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
internal fun GestureSettingsSection(gesturePreferences: GesturePreferences) {
    val settings by gesturePreferences.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Swipe distance", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Shorter = swipes (like delete-word) trigger more easily, but taps with a " +
                "little finger drift are more likely to be read as a swipe. Longer = the " +
                "opposite trade-off.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Short", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.swipeSensitivity,
                onValueChange = gesturePreferences::setSwipeSensitivity,
                valueRange = GestureSettings.MIN_SENSITIVITY..GestureSettings.MAX_SENSITIVITY,
                modifier = Modifier.weight(1f),
            )
            Text(text = "Long", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        SettingToggle(
            title = "Long press for special characters",
            description = "Long-press a key (like e, a, or the period) to pick an accent or " +
                "punctuation variant. Turning this off makes every long-press just repeat the tap.",
            checked = settings.showKeyPopup,
            onCheckedChange = gesturePreferences::setShowKeyPopup,
        )
        Spacer(Modifier.height(12.dp))
        SettingToggle(
            title = "Swipe right for space",
            description = "Swipe right anywhere on the keys to insert a space. Off by default.",
            checked = settings.swipeRightForSpace,
            onCheckedChange = gesturePreferences::setSwipeRightForSpace,
        )
    }
}

@Composable
internal fun FeedbackSettingsSection(preferences: HapticSoundPreferences, feedback: VibratorKeyboardFeedback) {
    val settings by preferences.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingToggle(
            title = "Haptic feedback",
            description = "A short vibration on every key press and a stronger one on swipes.",
            checked = settings.hapticEnabled,
            onCheckedChange = preferences::setHapticEnabled,
        )
        if (settings.hapticEnabled) {
            Text(text = "Strength", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Light", style = MaterialTheme.typography.bodySmall)
                Slider(
                    // onValueChangeFinished fires a preview tick at the strength the user just
                    // landed on — dragging a "strength" slider is meaningless without feeling the
                    // result immediately, a plain number tells you nothing.
                    value = settings.hapticStrength,
                    onValueChange = preferences::setHapticStrength,
                    onValueChangeFinished = { feedback.onKeyPress() },
                    valueRange = HapticSoundSettings.MIN_HAPTIC_STRENGTH..HapticSoundSettings.MAX_HAPTIC_STRENGTH,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "Strong", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingToggle(
            title = "Key sounds",
            description = "Plays a keypress click sound while typing.",
            checked = settings.soundEnabled,
            onCheckedChange = preferences::setSoundEnabled,
        )
        if (settings.soundEnabled) {
            Text(text = "Volume", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Quiet", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = settings.soundVolume,
                    onValueChange = preferences::setSoundVolume,
                    onValueChangeFinished = { feedback.onKeyPress() },
                    valueRange = HapticSoundSettings.MIN_SOUND_VOLUME..HapticSoundSettings.MAX_SOUND_VOLUME,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "Loud", style = MaterialTheme.typography.bodySmall)
            }
            Text(text = "Sound", style = MaterialTheme.typography.bodyLarge)
            SoundChoicePicker(preferences, settings.soundChoice, feedback)
        }
    }
}

/** Tap a row to both select it and hear it — mirrors the haptic-strength slider's
 * `onValueChangeFinished` preview-tick convention just above: a sound choice is meaningless to
 * pick from a label alone, the user needs to actually hear it. */
@Composable
private fun SoundChoicePicker(preferences: HapticSoundPreferences, currentChoice: String, feedback: VibratorKeyboardFeedback) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoundCatalog.displayNames.forEach { (id, name) ->
            val selected = id == currentChoice
            val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        preferences.setSoundChoice(id)
                        feedback.onKeyPress()
                    }
                    .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun AccessibleModeToggle(accessibilityPreferences: AccessibilityPreferences) {
    val enabled by accessibilityPreferences.forceAccessibleMode.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "Accessible mode", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Disables swipe gestures in favor of ordinary key taps, for use with " +
                    "TalkBack. Turns on automatically when TalkBack is active.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = accessibilityPreferences::setForceAccessibleMode)
    }
}

/** The one manual, opt-in network call in the whole app — see [dev.omakey.core.update.UpdateChecker]'s
 * own doc and the privacy notice above it. A plain button + inline status text, not a background
 * check: nothing happens until the user taps it, every single time. */
@Composable
internal fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateCheckStatus>(UpdateCheckStatus.Idle) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                status = UpdateCheckStatus.Checking
                scope.launch {
                    status = when (val outcome = dev.omakey.core.update.GithubReleaseUpdateChecker()
                        .checkForUpdate(dev.omakey.app.BuildConfig.VERSION_NAME)) {
                        is dev.omakey.core.update.UpdateCheckOutcome.Success -> UpdateCheckStatus.Checked(outcome.result)
                        dev.omakey.core.update.UpdateCheckOutcome.Error -> UpdateCheckStatus.Failed
                    }
                }
            },
            enabled = status !is UpdateCheckStatus.Checking,
        ) {
            Text(text = if (status is UpdateCheckStatus.Checking) "Checking..." else "Check for updates")
        }
        when (val current = status) {
            UpdateCheckStatus.Idle, UpdateCheckStatus.Checking -> Unit
            UpdateCheckStatus.Failed -> Text(
                text = "Couldn't check for updates — try again later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is UpdateCheckStatus.Checked -> if (current.result.updateAvailable) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Version ${current.result.latestVersion} is available.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(current.result.releaseUrl)))
                    }) {
                        Text(text = "View release")
                    }
                }
            } else {
                Text(
                    text = "You're up to date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface UpdateCheckStatus {
    data object Idle : UpdateCheckStatus
    data object Checking : UpdateCheckStatus
    data object Failed : UpdateCheckStatus
    data class Checked(val result: dev.omakey.core.update.UpdateCheckResult) : UpdateCheckStatus
}
