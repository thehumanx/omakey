package dev.omakey.app.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.R
import dev.omakey.app.keyboard.SoundCatalog
import dev.omakey.app.keyboard.VibratorKeyboardFeedback
import dev.omakey.app.keyboard.ui.FontCatalog
import dev.omakey.core.db.OmakeyDatabase
import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.feedback.HapticSoundSettings
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.gesture.GestureSettings
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.Layouts
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.theme.AccessibilityPreferences
import dev.omakey.core.theme.FontChoices
import dev.omakey.core.theme.FontPreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.ThemeRepository
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draws edge-to-edge (content behind the status/nav bars) so we control inset padding
        // ourselves via statusBarsPadding()/navigationBarsPadding() below — without this the
        // system draws an opaque status bar and the title row underneath it visually blends in.
        enableEdgeToEdge()
        val themeRepository = ThemeRepository(applicationContext)
        val accessibilityPreferences = AccessibilityPreferences(applicationContext)
        val layoutPreferences = LayoutPreferences(applicationContext)
        val fontPreferences = FontPreferences(applicationContext)
        val gesturePreferences = GesturePreferences(applicationContext)
        val hapticSoundPreferences = HapticSoundPreferences(applicationContext)
        val autocorrectPreferences = AutocorrectPreferences(applicationContext)
        val predictionPreferences = PredictionPreferences(applicationContext)
        val feedback = VibratorKeyboardFeedback(applicationContext, hapticSoundPreferences)
        val wordDao = OmakeyDatabase.getInstance(applicationContext).wordDao()
        setContent {
            OmakeySettingsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        themeRepository = themeRepository,
                        accessibilityPreferences = accessibilityPreferences,
                        layoutPreferences = layoutPreferences,
                        fontPreferences = fontPreferences,
                        gesturePreferences = gesturePreferences,
                        hapticSoundPreferences = hapticSoundPreferences,
                        autocorrectPreferences = autocorrectPreferences,
                        predictionPreferences = predictionPreferences,
                        wordDao = wordDao,
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

/** Neutral grey Material3 scheme — Compose's default `MaterialTheme { }` falls back to Material's
 * stock purple/violet palette (Purple40 etc.) for every unset role, which is where the previous
 * purple buttons/FAB/selection highlights actually came from (nothing in `OmakeyTheme`/`Presets`
 * is purple — those are the *keyboard's* theme, entirely separate from this Settings UI's own
 * Material theme). Overrides every role this screen actually paints with, rather than just
 * `primary`, so containers (buttons, FAB, selected-row highlight) don't fall back to Material's
 * purple-tinted defaults either. */
@Composable
private fun OmakeySettingsTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFB0B4B9),
            onPrimary = Color(0xFF1C1C1C),
            primaryContainer = Color(0xFF3A3A3D),
            onPrimaryContainer = Color(0xFFE8E8E8),
            secondary = Color(0xFF9AA0A6),
            onSecondary = Color(0xFF1C1C1C),
            surface = Color(0xFF141414),
            onSurface = Color(0xFFE8E8E8),
            surfaceVariant = Color(0xFF232323),
            onSurfaceVariant = Color(0xFFC8C8C8),
            background = Color(0xFF0F0F0F),
            onBackground = Color(0xFFE8E8E8),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF5F6368),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE1E3E5),
            onPrimaryContainer = Color(0xFF1C1C1C),
            secondary = Color(0xFF757575),
            onSecondary = Color.White,
            surface = Color(0xFFFDFDFD),
            onSurface = Color(0xFF1C1C1C),
            surfaceVariant = Color(0xFFF0F0F0),
            onSurfaceVariant = Color(0xFF444444),
            background = Color(0xFFFAFAFA),
            onBackground = Color(0xFF1C1C1C),
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun SettingsScreen(
    themeRepository: ThemeRepository,
    accessibilityPreferences: AccessibilityPreferences,
    layoutPreferences: LayoutPreferences,
    fontPreferences: FontPreferences,
    gesturePreferences: GesturePreferences,
    hapticSoundPreferences: HapticSoundPreferences,
    autocorrectPreferences: AutocorrectPreferences,
    predictionPreferences: PredictionPreferences,
    wordDao: WordDao,
    feedback: VibratorKeyboardFeedback,
    onOpenSystemSettings: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    val currentTheme by themeRepository.currentTheme.collectAsState()
    val currentFontId by fontPreferences.fontId.collectAsState()
    var showTestOverlay by remember { mutableStateOf(false) }
    var showLearnedWordsOverlay by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall) }

            item {
                SettingsSection(title = "Setup") {
                    Button(onClick = onOpenSystemSettings) {
                        Text(text = stringResource(R.string.settings_enable_keyboard))
                    }
                    Button(onClick = onSwitchKeyboard) {
                        Text(text = stringResource(R.string.settings_choose_keyboard))
                    }
                }
            }

            item {
                SettingsSection(title = "Appearance") {
                    Text(text = "Theme", style = MaterialTheme.typography.bodyLarge)
                    ThemePicker(themeRepository)
                    Text(text = "Font", style = MaterialTheme.typography.bodyLarge)
                    FontPicker(fontPreferences, currentFontId)
                }
            }

            item {
                SettingsSection(title = "Typing") {
                    AutocorrectToggle(autocorrectPreferences)
                    NextWordPredictionToggle(predictionPreferences)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(text = "Learned words", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Words your own typing has taught the keyboard — view, " +
                                    "search, or remove any that shouldn't have been learned.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { showLearnedWordsOverlay = true }) { Text(text = "Manage") }
                    }
                    KeyboardHeightEditor(layoutPreferences, currentTheme, currentFontId)
                    LayoutTogglesSection(layoutPreferences)
                    GestureSettingsSection(gesturePreferences)
                }
            }

            item {
                SettingsSection(title = "Sound & Haptics") {
                    FeedbackSettingsSection(hapticSoundPreferences, feedback)
                }
            }

            item {
                SettingsSection(title = "Accessibility") {
                    AccessibleModeToggle(accessibilityPreferences)
                }
            }

            item {
                SettingsSection(title = "About") {
                    Text(text = stringResource(R.string.privacy_notice), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        FloatingActionButton(
            onClick = { showTestOverlay = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
        ) {
            Text(text = "⌨", fontSize = 22.sp)
        }
    }

    if (showTestOverlay) {
        TestKeyboardOverlay(onClose = { showTestOverlay = false }, onSwitchKeyboard = onSwitchKeyboard)
    }
    if (showLearnedWordsOverlay) {
        LearnedWordsOverlay(wordDao = wordDao, onClose = { showLearnedWordsOverlay = false })
    }
}

/** Full-screen overlay listing every word the user's own typing has taught the dictionary
 * (`WordEntity.isUserAdded`) — lets a mistakenly-learned typo (see [AutocorrectIndex.learn]: once
 * a word is "known" it's never autocorrected away again, so a typo learned before the dictionary
 * was properly seeded, or just typed too fast to catch, otherwise has no way back) be removed
 * individually or all at once, with a live prefix search since the list can get long. Deleting
 * here only touches Room — an already-running IME's in-memory `AutocorrectIndex` reloads fresh
 * from Room at its own next startup rather than being live-notified, same load-once-at-startup
 * design as the rest of the dictionary (see AGENTS.md §6). */
@Composable
private fun LearnedWordsOverlay(wordDao: WordDao, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<WordEntity>>(emptyList()) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    suspend fun reload() {
        words = wordDao.findUserAdded(query.trim().lowercase())
    }
    LaunchedEffect(query) { reload() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Learned words", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text(text = "Close") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = "Search") },
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (words.isEmpty()) "No learned words" else "${words.size} word(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Scoped to the *unfiltered* list only — with a search query active, "delete all"
                // would ambiguously read as either "delete all matches" or "delete everything,
                // ignoring what's on screen." Hiding it while filtering sidesteps the ambiguity
                // rather than guessing which one the user means.
                if (words.isNotEmpty() && query.isBlank()) {
                    TextButton(onClick = { showDeleteAllConfirm = true }) { Text(text = "Delete all") }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(words, key = { it.word }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = entry.word, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                scope.launch {
                                    wordDao.delete(entry.word)
                                    reload()
                                }
                            },
                        ) { Text(text = "Remove") }
                    }
                }
            }
        }
    }

    if (showDeleteAllConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(text = "Delete all learned words?") },
            text = { Text(text = "This removes every word your typing has taught the keyboard. The bundled dictionary is not affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllConfirm = false
                        scope.launch {
                            wordDao.deleteAllUserAdded()
                            reload()
                        }
                    },
                ) { Text(text = "Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text(text = "Cancel") }
            },
        )
    }
}

/** Groups related settings into a labeled, visually distinct card instead of a flat list of raw
 * text headers — the previous layout put every section at the same visual weight, which read as
 * "everything is one long list" rather than a set of related groups. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
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

/** Full-screen overlay hosting a plain focused text field — focusing it brings up whichever IME
 * is currently the system default, same as focusing any text field in any real app. Only actually
 * exercises Omakey if it's the active keyboard, hence the banner below when it isn't. */
@Composable
private fun TestKeyboardOverlay(onClose: () -> Unit, onSwitchKeyboard: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isOmakeyActive = remember { isOmakeyDefaultIme(context) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Test your keyboard", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text(text = "Close") }
            }

            if (!isOmakeyActive) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Omakey isn't your active keyboard — switch to it to test typing here.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onSwitchKeyboard) { Text(text = "Switch") }
                    }
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text(text = "Tap here and start typing…") },
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

/** Best-effort check via the flattened default-IME component name in Settings.Secure — good
 * enough to decide whether to show the "switch keyboard" nudge, not used for anything security
 * sensitive. */
private fun isOmakeyDefaultIme(context: android.content.Context): Boolean {
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return current?.contains(context.packageName) == true
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
private fun AutocorrectToggle(autocorrectPreferences: AutocorrectPreferences) {
    val settings by autocorrectPreferences.settings.collectAsState()
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
private fun NextWordPredictionToggle(predictionPreferences: PredictionPreferences) {
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
            description = "Plays a keypress click sound while typing.",
            checked = settings.soundEnabled,
            onCheckedChange = preferences::setSoundEnabled,
        )
        if (settings.soundEnabled) {
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
                if (selected) {
                    Text(text = "✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
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
