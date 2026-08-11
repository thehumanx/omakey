package dev.omakey.app.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.R
import dev.omakey.app.keyboard.VibratorKeyboardFeedback
import dev.omakey.app.keyboard.ui.FontCatalog
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.feedback.HapticSoundSettings
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.theme.AccessibilityPreferences
import dev.omakey.core.theme.FontChoices
import dev.omakey.core.theme.FontPreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.ThemeRepository
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeRepository = ThemeRepository(applicationContext)
        val accessibilityPreferences = AccessibilityPreferences(applicationContext)
        val layoutPreferences = LayoutPreferences(applicationContext)
        val fontPreferences = FontPreferences(applicationContext)
        val gesturePreferences = GesturePreferences(applicationContext)
        val hapticSoundPreferences = HapticSoundPreferences(applicationContext)
        val feedback = VibratorKeyboardFeedback(applicationContext, hapticSoundPreferences)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        themeRepository = themeRepository,
                        accessibilityPreferences = accessibilityPreferences,
                        layoutPreferences = layoutPreferences,
                        fontPreferences = fontPreferences,
                        gesturePreferences = gesturePreferences,
                        hapticSoundPreferences = hapticSoundPreferences,
                        feedback = feedback,
                        onOpenSystemSettings = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                        onSwitchKeyboard = {
                            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager
                            imm.showInputMethodPicker()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    themeRepository: ThemeRepository,
    accessibilityPreferences: AccessibilityPreferences,
    layoutPreferences: LayoutPreferences,
    fontPreferences: FontPreferences,
    gesturePreferences: GesturePreferences,
    hapticSoundPreferences: HapticSoundPreferences,
    feedback: VibratorKeyboardFeedback,
    onOpenSystemSettings: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    val currentTheme by themeRepository.currentTheme.collectAsState()
    val currentFontId by fontPreferences.fontId.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) }
        item {
            Button(onClick = onOpenSystemSettings) {
                Text(text = stringResource(R.string.settings_enable_keyboard))
            }
        }
        item {
            Button(onClick = onSwitchKeyboard) {
                Text(text = stringResource(R.string.settings_choose_keyboard))
            }
        }

        item { Text(text = "Theme", style = MaterialTheme.typography.titleMedium) }
        item { ThemePicker(themeRepository) }

        item { Text(text = "Font", style = MaterialTheme.typography.titleMedium) }
        item { FontPicker(fontPreferences, currentFontId) }

        item { Text(text = "Keyboard layout", style = MaterialTheme.typography.titleMedium) }
        item { KeyboardHeightEditor(layoutPreferences, currentTheme, currentFontId) }
        item { LayoutTogglesSection(layoutPreferences) }

        item { Text(text = "Gestures", style = MaterialTheme.typography.titleMedium) }
        item { GestureSettingsSection(gesturePreferences) }

        item { Text(text = "Feedback", style = MaterialTheme.typography.titleMedium) }
        item { FeedbackSettingsSection(hapticSoundPreferences, feedback) }

        item { Text(text = "Accessibility", style = MaterialTheme.typography.titleMedium) }
        item { AccessibleModeToggle(accessibilityPreferences) }

        item { Text(text = stringResource(R.string.privacy_notice), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun FontPicker(fontPreferences: FontPreferences, currentFontId: String) {
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
                if (selected) {
                    Text(text = "✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun LayoutTogglesSection(layoutPreferences: LayoutPreferences) {
    val settings by layoutPreferences.settings.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingToggle(
            title = "Boxed keys",
            description = "Shows a background box behind every key, instead of the flat default.",
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
private fun SettingToggle(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

/** Drag the handle below the preview to resize the keyboard. Renders the actual KeyRowView used
 * by the real keyboard (not a mock) so the height change is judged against real key rendering —
 * letters, spacebar accent, home-row stripe, boxed-keys setting, all exactly as they'll appear
 * when typing. Non-interactive: taps don't type anything, since there's no InputConnection here. */
@Composable
private fun KeyboardHeightEditor(layoutPreferences: LayoutPreferences, theme: OmakeyTheme, fontId: String) {
    val settings by layoutPreferences.settings.collectAsState()
    val density = LocalDensity.current
    var heightDp by remember(settings.keyboardHeightDp) { mutableFloatStateOf(settings.keyboardHeightDp.toFloat()) }
    val fontFamily = remember(fontId) { FontCatalog.resolve(fontId) }

    val rows = Layouts.QwertyEnUS.rows
    val rowHeightDp = (heightDp.roundToInt() / Layouts.QwertyEnUS.rows.size)
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Height: ${heightDp.roundToInt()}dp — drag the handle below",
            style = MaterialTheme.typography.bodySmall,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height((rowHeightDp * rows.size).dp)
                .background(theme.keyboardBackground.toComposeColor(), RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    dev.omakey.app.keyboard.ui.KeyRowView(
                        rowKeys = row.keys,
                        rowHeightDp = rowHeightDp,
                        shiftOn = false,
                        theme = theme,
                        accessibleMode = false,
                        showKeyBackgrounds = settings.showKeyBackgrounds,
                        isHomeRow = settings.showMiddleRowStripe && index == 1,
                        onKeyTap = {},
                        ancestorCoordinates = noOpAncestor,
                        onBoundsMeasured = { _, _, _ -> },
                        fontFamily = fontFamily,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { deltaPx ->
                        heightDp = with(density) { (heightDp.dp + deltaPx.toDp()).value }
                            .coerceIn(LayoutSettings.MIN_HEIGHT_DP.toFloat(), LayoutSettings.MAX_HEIGHT_DP.toFloat())
                    },
                    onDragStopped = { layoutPreferences.setKeyboardHeightDp(heightDp.roundToInt()) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun GestureSettingsSection(gesturePreferences: GesturePreferences) {
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
        SettingToggle(
            title = "Key preview popup",
            description = "Long-press a key (like e, a, or the period) to pick an accent or " +
                "punctuation variant. Turning this off makes every long-press just repeat the tap.",
            checked = settings.showKeyPopup,
            onCheckedChange = gesturePreferences::setShowKeyPopup,
        )
    }
}

@Composable
private fun FeedbackSettingsSection(preferences: HapticSoundPreferences, feedback: VibratorKeyboardFeedback) {
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
        SettingToggle(
            title = "Key sounds",
            description = "Plays the system keypress click sound while typing.",
            checked = settings.soundEnabled,
            onCheckedChange = preferences::setSoundEnabled,
        )
    }
}

@Composable
private fun AccessibleModeToggle(accessibilityPreferences: AccessibilityPreferences) {
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

@Composable
private fun ThemePicker(themeRepository: ThemeRepository) {
    val currentTheme by themeRepository.currentTheme.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Presets.all.forEach { preset ->
            ThemeRow(
                theme = preset,
                selected = preset.id == currentTheme.id,
                onClick = { themeRepository.setTheme(preset) },
            )
        }
    }
}

@Composable
private fun ThemeRow(theme: OmakeyTheme, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KeyboardSwatch(theme)
        Column(Modifier.weight(1f)) {
            Text(text = theme.name, style = MaterialTheme.typography.bodyLarge)
        }
        if (selected) {
            Text(text = "✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Tiny non-interactive preview of a theme's palette — a compact swatch, not a full live keyboard
 * preview, since rendering a real KeyboardRoot here would require the full IME dependency graph
 * (InputConnection, prediction engine, extension registry) that doesn't exist outside the
 * service. Good enough to judge a theme's colors/shape at a glance. */
@Composable
private fun KeyboardSwatch(theme: OmakeyTheme) {
    val shape = when (theme.keyShape) {
        dev.omakey.core.theme.KeyShape.PILL -> RoundedCornerShape(50)
        dev.omakey.core.theme.KeyShape.SQUARE -> RoundedCornerShape(0.dp)
        dev.omakey.core.theme.KeyShape.ROUNDED -> RoundedCornerShape(6.dp)
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(theme.keyboardBackground.toComposeColor(), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(theme.keyBackground.toComposeColor(), shape),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .size(width = 10.dp, height = 10.dp)
                        .background(theme.keySpecialBackground.toComposeColor(), shape),
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .background(theme.keyBackgroundPressed.toComposeColor(), CircleShape),
                )
            }
        }
    }
}

private fun dev.omakey.core.theme.ColorSpec.toComposeColor() = Color(argb.toInt())
