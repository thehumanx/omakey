package dev.omakey.app.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.omakey.core.theme.ColorSpec
import dev.omakey.core.theme.CustomThemePreferences
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
        val customThemePreferences = CustomThemePreferences(applicationContext)
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
                        customThemePreferences = customThemePreferences,
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
    customThemePreferences: CustomThemePreferences,
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
    val useSystemAccent by themeRepository.useSystemAccent.collectAsState()
    // The raw stored theme (above) is what ThemePicker highlights as "selected" — this resolved
    // version (following "Follow system"/"pick accent from system" if either is on) is what every
    // live preview mock should actually show, so previews reflect what really gets applied.
    val effectiveTheme = dev.omakey.app.keyboard.resolveEffectiveTheme(currentTheme, useSystemAccent)
    val currentFontId by fontPreferences.fontId.collectAsState()
    var showTestOverlay by remember { mutableStateOf(false) }
    var showLearnedWordsOverlay by remember { mutableStateOf(false) }
    var showSizePositionOverlay by remember { mutableStateOf(false) }
    // Hoisted up from ThemePicker (which lives inside a LazyColumn item) rather than kept local
    // there — ThemeEditorOverlay's Modifier.verticalScroll() crashes with "measured with an
    // infinity maximum height constraints" if composed as a LazyColumn item's descendant, the
    // classic nested-scrollable-in-unbounded-height bug. Every other full-screen overlay here
    // (TestKeyboardOverlay, LearnedWordsOverlay, KeyboardSizePositionOverlay) is already rendered
    // from this top-level Box for the same reason — ThemeEditorOverlay just hadn't followed suit.
    var showThemeEditor by remember { mutableStateOf(false) }
    var themeBeingEdited by remember { mutableStateOf<OmakeyTheme?>(null) }

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
                    val (isEnabled, isDefault) = rememberSetupStatus()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SetupStatusIcon(done = isEnabled)
                        Button(onClick = onOpenSystemSettings, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(text = stringResource(R.string.settings_enable_keyboard))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SetupStatusIcon(done = isDefault)
                        Button(onClick = onSwitchKeyboard, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(text = stringResource(R.string.settings_choose_keyboard))
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "Appearance") {
                    Text(text = "Theme", style = MaterialTheme.typography.bodyLarge)
                    ThemePicker(
                        themeRepository = themeRepository,
                        customThemePreferences = customThemePreferences,
                        onCreateTheme = { themeBeingEdited = null; showThemeEditor = true },
                        onEditTheme = { theme -> themeBeingEdited = theme; showThemeEditor = true },
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        SettingToggle(
                            title = "Pick accent color from system",
                            description = "Use your device's Material You accent color for the " +
                                "spacebar instead of the theme's own.",
                            checked = useSystemAccent,
                            onCheckedChange = themeRepository::setUseSystemAccent,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = "Font", style = MaterialTheme.typography.bodyLarge)
                    FontPicker(fontPreferences, currentFontId)
                }
            }

            item {
                SettingsSection(title = "Typing") {
                    AutocorrectToggle(autocorrectPreferences)
                    AutoCapitalizeToggle(autocorrectPreferences)
                    NextWordPredictionToggle(predictionPreferences)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ClickableSettingRow(
                        title = "Learned words",
                        description = "Words your own typing has taught the keyboard — view, " +
                            "search, or remove any that shouldn't have been learned.",
                        onClick = { showLearnedWordsOverlay = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ClickableSettingRow(
                        title = "Keyboard size & position",
                        description = "Resize the keyboard and raise it off the bottom edge for " +
                            "easier one-handed thumb reach — drag to adjust both.",
                        onClick = { showSizePositionOverlay = true },
                    )
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
                    Text(
                        text = "Version ${dev.omakey.app.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    if (showSizePositionOverlay) {
        KeyboardSizePositionOverlay(
            layoutPreferences = layoutPreferences,
            theme = effectiveTheme,
            fontId = currentFontId,
            onClose = { showSizePositionOverlay = false },
        )
    }
    if (showThemeEditor) {
        ThemeEditorOverlay(
            initialTheme = themeBeingEdited,
            onSave = { theme ->
                customThemePreferences.save(theme)
                themeRepository.setTheme(theme)
                showThemeEditor = false
            },
            onClose = { showThemeEditor = false },
        )
    }
}

/** Combined drag-to-resize + drag-to-position overlay — used to be two separate screens
 * ("Keyboard height" and "Keyboard position"), merged into one since they're both "make the
 * keyboard mock at 1:1 scale and drag part of it" interactions and were confusing to keep
 * separate. Two independent drag affordances on the same live [KeyRowView] preview:
 * - The handle bar above the preview resizes [LayoutSettings.keyboardHeightDp]. Dragging it *up*
 *   makes the keyboard taller (like pulling a window edge outward), dragging it *down* makes it
 *   shorter.
 * - The pill-shaped grip centered *inside* the preview repositions [LayoutSettings.bottomOffsetDp]
 *   — drag up to raise the keyboard off the bottom edge for easier one-handed thumb reach, capped
 *   so it can never be dragged up past the vertical center of the screen.
 * Both act on the same live-updating preview so the effect of one is visible while adjusting the
 * other, instead of needing to bounce between two separate screens to get the combination right. */
@Composable
private fun KeyboardSizePositionOverlay(
    layoutPreferences: LayoutPreferences,
    theme: OmakeyTheme,
    fontId: String,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val settings by layoutPreferences.settings.collectAsState()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val fontFamily = remember(fontId) { FontCatalog.resolve(fontId) }
    var heightDp by remember(settings.keyboardHeightDp) { mutableFloatStateOf(settings.keyboardHeightDp.toFloat()) }
    var offsetDp by remember(settings.bottomOffsetDp) { mutableFloatStateOf(settings.bottomOffsetDp.toFloat()) }

    val rows = Layouts.QwertyEnUS.rows
    val rowHeightDp = (heightDp.roundToInt() / rows.size)
    val keyboardTotalHeightDp = rowHeightDp * rows.size
    val maxOffsetDp = (configuration.screenHeightDp / 2f - keyboardTotalHeightDp).coerceAtLeast(0f)
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Keyboard size & position", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text(text = "Done") }
                }
                Text(
                    text = "Height: ${heightDp.roundToInt()}dp — drag the handle to resize. Drag " +
                        "the grip inside the preview to move it up or down; it's capped at the " +
                        "middle of the screen so there's always room to see what you're typing into.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Resize handle, directly above the preview — dragging it up (finger moves toward the
            // top of the screen, negative deltaPx) grows the keyboard, matching the intuitive
            // "pull the edge outward to make it bigger" gesture; dragging down shrinks it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { deltaPx ->
                            heightDp = with(density) { (heightDp.dp - deltaPx.toDp()).value }
                                .coerceIn(LayoutSettings.MIN_HEIGHT_DP.toFloat(), LayoutSettings.MAX_HEIGHT_DP.toFloat())
                        },
                        onDragStopped = { layoutPreferences.setKeyboardHeightDp(heightDp.roundToInt()) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 48.dp, height = 5.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(keyboardTotalHeightDp.dp)
                    .background(theme.keyboardBackground.toComposeColor()),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
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
                            alwaysShowUppercaseLetters = settings.alwaysShowUppercaseLetters,
                        )
                    }
                }
                // Position grip — centered inside the preview, its own draggable so it doesn't
                // fight the resize handle above or accidentally trigger on ordinary key taps
                // elsewhere in the preview (this mock isn't otherwise interactive anyway).
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(width = 56.dp, height = 28.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { deltaPx ->
                                // Dragging up (negative deltaPx, since y decreases upward) raises
                                // the keyboard — i.e. increases the offset below it.
                                offsetDp = (offsetDp - with(density) { deltaPx.toDp().value }).coerceIn(0f, maxOffsetDp)
                            },
                            onDragStopped = { layoutPreferences.setBottomOffsetDp(offsetDp.roundToInt()) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 28.dp, height = 5.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                    )
                }
            }
            if (offsetDp > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(offsetDp.dp)
                        .background(theme.keyboardBackground.toComposeColor()),
                )
            }
            Box(Modifier.fillMaxWidth().navigationBarsPadding())
        }
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
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<WordEntity>>(emptyList()) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var wordBeingEdited by remember { mutableStateOf<WordEntity?>(null) }

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
                        TextButton(onClick = { wordBeingEdited = entry }) { Text(text = "Edit") }
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

    wordBeingEdited?.let { entry ->
        var editedWord by remember(entry.word) { mutableStateOf(entry.word) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { wordBeingEdited = null },
            title = { Text(text = "Edit word") },
            text = {
                OutlinedTextField(
                    value = editedWord,
                    onValueChange = { editedWord = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newWord = editedWord.trim().lowercase()
                        wordBeingEdited = null
                        if (newWord.isNotEmpty() && newWord != entry.word) {
                            scope.launch {
                                wordDao.rename(entry.word, newWord)
                                reload()
                            }
                        }
                    },
                ) { Text(text = "Save") }
            },
            dismissButton = {
                TextButton(onClick = { wordBeingEdited = null }) { Text(text = "Cancel") }
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
 * exercises omakey if it's the active keyboard, hence the banner below when it isn't. */
@Composable
private fun TestKeyboardOverlay(onClose: () -> Unit, onSwitchKeyboard: () -> Unit) {
    BackHandler(onBack = onClose)
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
                            text = "omakey isn't your active keyboard — switch to it to test typing here.",
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
private fun rememberSetupStatus(): Pair<Boolean, Boolean> {
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
private fun SetupStatusIcon(done: Boolean) {
    Text(
        text = if (done) "✓" else "!",
        color = if (done) androidx.compose.ui.graphics.Color(0xFF3A9D5D) else androidx.compose.ui.graphics.Color(0xFFC77B00),
        style = MaterialTheme.typography.titleMedium,
    )
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
private fun AutoCapitalizeToggle(autocorrectPreferences: AutocorrectPreferences) {
    val settings by autocorrectPreferences.settings.collectAsState()
    SettingToggle(
        title = "Auto-capitalize",
        description = "Capitalizes the first letter of a new field and after sentence-ending " +
            "punctuation (. ! ?). Off by default.",
        checked = settings.autoCapitalizeEnabled,
        onCheckedChange = autocorrectPreferences::setAutoCapitalizeEnabled,
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
        SettingToggle(
            title = "Always show capital letters",
            description = "Keycaps always show uppercase letters, regardless of shift state " +
                "(omakey's default look). Turn off for the usual keyboard behavior — lowercase " +
                "keycaps that switch to uppercase only while shift is on.",
            checked = settings.alwaysShowUppercaseLetters,
            onCheckedChange = layoutPreferences::setAlwaysShowUppercaseLetters,
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

/** A settings row that opens something (an overlay, a picker) — the whole row is the tap target,
 * not just a trailing button, per standard Android settings-list UX (Settings app itself, most
 * system preference screens). The trailing "❯" is a plain visual affordance, not itself
 * interactive — it doesn't need to be, since the whole row already is. */
@Composable
private fun ClickableSettingRow(title: String, description: String, onClick: () -> Unit) {
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
        SettingToggle(
            title = "Swipe right for space",
            description = "Swipe right anywhere on the keys to insert a space. Off by default.",
            checked = settings.swipeRightForSpace,
            onCheckedChange = gesturePreferences::setSwipeRightForSpace,
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
private fun ThemePicker(
    themeRepository: ThemeRepository,
    customThemePreferences: CustomThemePreferences,
    onCreateTheme: () -> Unit,
    onEditTheme: (OmakeyTheme) -> Unit,
) {
    val currentTheme by themeRepository.currentTheme.collectAsState()
    val customThemes by customThemePreferences.themes.collectAsState()
    var themeToDelete by remember { mutableStateOf<OmakeyTheme?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (Presets.all + customThemes).forEach { theme ->
            val isCustom = theme.id.startsWith(CustomThemePreferences.ID_PREFIX)
            ThemeRow(
                theme = theme,
                selected = theme.id == currentTheme.id,
                onClick = { themeRepository.setTheme(theme) },
                onEdit = if (isCustom) { { onEditTheme(theme) } } else null,
                onDelete = if (isCustom) { { themeToDelete = theme } } else null,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCreateTheme)
                .border(width = 2.dp, color = Color.Transparent, shape = RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(text = "Create your own theme", style = MaterialTheme.typography.bodyLarge)
        }
    }

    // AlertDialog renders via Compose's own Dialog composable under the hood, which draws in a
    // separate window with its own constraints — safe to keep here even though ThemePicker itself
    // sits inside a LazyColumn item. ThemeEditorOverlay does NOT get this for free (see below).
    themeToDelete?.let { theme ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { themeToDelete = null },
            title = { Text(text = "Delete \"${theme.name}\"?") },
            text = { Text(text = "This can't be undone. If it's the theme currently applied, the keyboard falls back to Dark.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        customThemePreferences.delete(theme.id)
                        if (currentTheme.id == theme.id) themeRepository.setTheme(Presets.Dark)
                        themeToDelete = null
                    },
                ) { Text(text = "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { themeToDelete = null }) { Text(text = "Cancel") }
            },
        )
    }
}

@Composable
private fun ThemeRow(
    theme: OmakeyTheme,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
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
        if (onEdit != null) {
            TextButton(onClick = onEdit) { Text(text = "Edit") }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text(text = "Delete") }
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

/** "Build your own theme" — full-screen editor for exactly the 4 colors requested (background,
 * key, home-row stripe, spacebar), matching [LearnedWordsOverlay]'s full-screen-overlay pattern.
 * Every other [OmakeyTheme] field (text color, pressed/special key backgrounds, suggestion bar,
 * `isDark`) is derived automatically from those 4 via simple luminance rules in
 * [buildCustomTheme] — a 9-field form would defeat the point of scoping this to "the 4 colors a
 * user actually thinks about." [initialTheme] non-null means editing an existing custom theme
 * (keeps its id); null means creating a new one. */
@Composable
private fun ThemeEditorOverlay(initialTheme: OmakeyTheme?, onSave: (OmakeyTheme) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var name by remember { mutableStateOf(initialTheme?.name ?: "My theme") }
    var background by remember { mutableStateOf(initialTheme?.keyboardBackground?.toComposeColor() ?: Color(0xFF1E1E1E)) }
    var keyColor by remember { mutableStateOf(initialTheme?.keyBackground?.toComposeColor() ?: Color(0xFF2C2C2C)) }
    var stripeColor by remember { mutableStateOf(initialTheme?.middleRowStripeColor?.toComposeColor() ?: Color(0xFF3A3A3A)) }
    var spacebarColor by remember { mutableStateOf(initialTheme?.spacebarAccentColor?.toComposeColor() ?: Color(0xFF4A90D9)) }

    val previewTheme = remember(name, background, keyColor, stripeColor, spacebarColor) {
        buildCustomTheme(
            id = initialTheme?.id ?: (CustomThemePreferences.ID_PREFIX + java.util.UUID.randomUUID().toString()),
            name = name.ifBlank { "My theme" },
            backgroundColor = background,
            keyColor = keyColor,
            stripeColor = stripeColor,
            spacebarColor = spacebarColor,
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (initialTheme == null) "Create theme" else "Edit theme",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onClose) { Text(text = "Cancel") }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(text = "Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Text(text = "Preview", style = MaterialTheme.typography.bodyLarge)
            ThemePreviewMock(previewTheme)

            ColorPickerField(label = "Background", color = background, onColorChange = { background = it })
            ColorPickerField(label = "Key color", color = keyColor, onColorChange = { keyColor = it })
            ColorPickerField(label = "Home-row stripe", color = stripeColor, onColorChange = { stripeColor = it })
            ColorPickerField(label = "Spacebar", color = spacebarColor, onColorChange = { spacebarColor = it })

            Button(onClick = { onSave(previewTheme) }, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Save theme")
            }
        }
    }
}

/** A real `KeyRowView` row (same composable the actual keyboard renders, reused rather than a
 * bespoke mock — same trick as `KeyboardSizePositionOverlay`) so the editor's preview is
 * pixel-accurate to what typing will actually look like. */
@Composable
private fun ThemePreviewMock(theme: OmakeyTheme) {
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(theme.keyboardBackground.toComposeColor(), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp),
    ) {
        dev.omakey.app.keyboard.ui.KeyRowView(
            rowKeys = Layouts.QwertyEnUS.rows[3].keys,
            rowHeightDp = 56,
            shiftOn = false,
            theme = theme,
            accessibleMode = false,
            showKeyBackgrounds = false,
            isHomeRow = false,
            onKeyTap = {},
            ancestorCoordinates = noOpAncestor,
            onBoundsMeasured = { _, _, _ -> },
        )
    }
}

/** A labeled swatch that expands an [HsvColorPicker] inline when tapped — collapsed by default so
 * 4 pickers don't all fight for screen space at once. */
@Composable
private fun ColorPickerField(label: String, color: Color, onColorChange: (Color) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(color, RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
            )
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text = if (expanded) "▲" else "▼", style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            HsvColorPicker(color = color, onColorChange = onColorChange)
        }
    }
}

/** A plain hue-strip + saturation/value square, drawn with `Canvas`-free layered gradients
 * (`Modifier.background(Brush...)`, composited via normal alpha blending) rather than a new
 * dependency — Compose has no built-in color picker. Tap or drag either control to update. */
@Composable
private fun HsvColorPicker(color: Color, onColorChange: (Color) -> Unit) {
    val initialHsv = remember(Unit) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), out)
        out
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    fun emit() {
        onColorChange(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))))
    }

    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .size(160.dp)
                .background(hueColor)
                .background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        saturation = (offset.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * saturation - 6.dp, y = maxHeight * (1f - value) - 6.dp)
                    .size(12.dp)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(160.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                    ),
                )
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        hue = (offset.y / size.height).coerceIn(0f, 1f) * 360f
                        emit()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        hue = (change.position.y / size.height).coerceIn(0f, 1f) * 360f
                        emit()
                    }
                },
        )
    }
}

/** Derives a complete [OmakeyTheme] from just the 4 colors the editor exposes — text color picks
 * black/white by the key color's relative luminance (light key → dark text, and vice versa);
 * pressed/special key backgrounds are the base key color nudged lighter (dark themes) or darker
 * (light themes); the suggestion bar is the background color nudged the same way. Keeps the
 * editor's UI to exactly 4 fields instead of exposing all 9 of [OmakeyTheme]'s color fields. */
private fun buildCustomTheme(
    id: String,
    name: String,
    backgroundColor: Color,
    keyColor: Color,
    stripeColor: Color,
    spacebarColor: Color,
): OmakeyTheme {
    val isDark = relativeLuminance(keyColor) < 0.5f
    val textColor = if (isDark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    val nudge = if (isDark) 0.15f else -0.15f
    val smallNudge = if (isDark) 0.08f else -0.08f
    return OmakeyTheme(
        id = id,
        name = name,
        isDark = isDark,
        keyboardBackground = backgroundColor.toColorSpec(),
        keyBackground = keyColor.toColorSpec(),
        keyBackgroundPressed = nudgeColor(keyColor, nudge).toColorSpec(),
        keyTextColor = textColor.toColorSpec(),
        keySpecialBackground = nudgeColor(keyColor, smallNudge).toColorSpec(),
        suggestionBarBackground = nudgeColor(backgroundColor, smallNudge).toColorSpec(),
        spacebarAccentColor = spacebarColor.toColorSpec(),
        middleRowStripeColor = stripeColor.toColorSpec(),
    )
}

private fun relativeLuminance(color: Color): Float = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

private fun nudgeColor(color: Color, amount: Float): Color = Color(
    red = (color.red + amount).coerceIn(0f, 1f),
    green = (color.green + amount).coerceIn(0f, 1f),
    blue = (color.blue + amount).coerceIn(0f, 1f),
    alpha = color.alpha,
)

private fun Color.toColorSpec(): ColorSpec = ColorSpec.fromArgbInt(this.toArgb())
