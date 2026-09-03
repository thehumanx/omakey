package dev.omakey.app.settings

import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.R
import dev.omakey.app.keyboard.SoundCatalog
import dev.omakey.app.keyboard.VibratorKeyboardFeedback
import dev.omakey.app.keyboard.ui.FontCatalog
import dev.omakey.core.icons.PhosphorCopy
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
import dev.omakey.core.predict.IncognitoPreferences
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
    // Requested only in direct response to the user flipping "Automatic update checks" on (see
    // UpdateCheckRow) — Android 13+ ties POST_NOTIFICATIONS to an explicit runtime prompt, and
    // firing it unprompted at Activity launch (before the user has expressed any intent around
    // updates at all) would just be a cold permission dialog with no context. If denied, the
    // periodic worker still runs on schedule (see UpdateCheckWorker) but silently skips posting
    // the notification rather than crashing.
    private val notificationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}

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
        val incognitoPreferences = IncognitoPreferences(applicationContext)
        val updatePreferences = dev.omakey.core.update.UpdatePreferences(applicationContext)
        val feedback = VibratorKeyboardFeedback(applicationContext, hapticSoundPreferences)
        val wordDao = OmakeyDatabase.getInstance(applicationContext).wordDao()
        // Idempotent (see UpdateWorkScheduler's own doc) — also scheduled from the IME service,
        // this covers the case where Settings is opened before the keyboard has ever been enabled.
        if (updatePreferences.settings.value.autoCheckEnabled) {
            dev.omakey.app.update.UpdateWorkScheduler.schedule(applicationContext)
        }
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
                        incognitoPreferences = incognitoPreferences,
                        updatePreferences = updatePreferences,
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
                        onAutoUpdateCheckToggled = { enabled ->
                            updatePreferences.setAutoCheckEnabled(enabled)
                            if (enabled) {
                                dev.omakey.app.update.UpdateWorkScheduler.schedule(applicationContext)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        this@SettingsActivity, android.Manifest.permission.POST_NOTIFICATIONS,
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                dev.omakey.app.update.UpdateWorkScheduler.cancel(applicationContext)
                            }
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
    incognitoPreferences: IncognitoPreferences,
    updatePreferences: dev.omakey.core.update.UpdatePreferences,
    wordDao: WordDao,
    feedback: VibratorKeyboardFeedback,
    onOpenSystemSettings: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onAutoUpdateCheckToggled: (Boolean) -> Unit,
) {
    val currentTheme by themeRepository.currentTheme.collectAsState()
    val useSystemAccent by themeRepository.useSystemAccent.collectAsState()
    val layoutMode by themeRepository.layoutMode.collectAsState()
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
                    // Shown first — Normal vs. Grid decides how the rest of this section's
                    // options apply (e.g. "Key backgrounds" is a Normal-mode-only concept; Grid
                    // mode always shows bordered cells regardless of that toggle).
                    Text(text = "Layout style", style = MaterialTheme.typography.bodyLarge)
                    LayoutModePicker(themeRepository)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = "Theme", style = MaterialTheme.typography.bodyLarge)
                    ThemePicker(
                        themeRepository = themeRepository,
                        customThemePreferences = customThemePreferences,
                        layoutMode = layoutMode,
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
                    AppearanceLayoutToggles(layoutPreferences)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = "Font", style = MaterialTheme.typography.bodyLarge)
                    FontPicker(fontPreferences, currentFontId)
                }
            }

            item {
                SettingsSection(title = "Typing") {
                    AutocorrectToggle(autocorrectPreferences)
                    AutoCapitalizeToggle(autocorrectPreferences)
                    DoubleTapSpaceForPeriodToggle(autocorrectPreferences)
                    NextWordPredictionToggle(predictionPreferences)
                    ImplicitLearningToggle(incognitoPreferences)
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
                    CapitalizationToggleSection(layoutPreferences)
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
                    val autoCheckEnabled by updatePreferences.settings.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(text = "Automatic update checks", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Checks GitHub every 12 hours and notifies you if a new " +
                                    "version is out. No background download or install.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = autoCheckEnabled.autoCheckEnabled,
                            onCheckedChange = onAutoUpdateCheckToggled,
                        )
                    }
                    UpdateCheckRow()
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
            layoutMode = layoutMode,
            fontId = currentFontId,
            onClose = { showSizePositionOverlay = false },
        )
    }
    if (showThemeEditor) {
        ThemeEditorOverlay(
            initialTheme = themeBeingEdited,
            layoutMode = layoutMode,
            layoutPreferences = layoutPreferences,
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
    layoutMode: dev.omakey.core.theme.LayoutMode,
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
                // Provides the user's actual current layout mode — without this,
                // LocalKeyboardLayoutMode defaults to NORMAL (nothing else in Settings' compose
                // tree provides it), so this preview would always show Normal-mode keys even with
                // Grid mode active, same bug ThemeEditorOverlay's preview had.
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.omakey.core.theme.LocalKeyboardLayoutMode provides layoutMode,
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
                                onBoundsMeasured = {},
                                fontFamily = fontFamily,
                                alwaysShowUppercaseLetters = settings.alwaysShowUppercaseLetters,
                            )
                        }
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
private fun DoubleTapSpaceForPeriodToggle(autocorrectPreferences: AutocorrectPreferences) {
    val settings by autocorrectPreferences.settings.collectAsState()
    SettingToggle(
        title = "Double-tap space for period",
        description = "Tap (or swipe right, if that's enabled) space twice quickly to insert " +
            "a period instead of two spaces. Off by default.",
        checked = settings.doubleTapSpaceForPeriod,
        onCheckedChange = autocorrectPreferences::setDoubleTapSpaceForPeriod,
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
private fun ImplicitLearningToggle(incognitoPreferences: IncognitoPreferences) {
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
@Composable
private fun AppearanceLayoutToggles(layoutPreferences: LayoutPreferences) {
    val settings by layoutPreferences.settings.collectAsState()
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
        SettingToggle(
            title = "Show key press popup",
            description = "Briefly shows an enlarged copy of the letter above your finger on " +
                "every tap. Distinct from \"Long press for special characters\" — this one is " +
                "the ordinary per-tap preview, not the held-key accent picker.",
            checked = settings.showTapPreview,
            onCheckedChange = layoutPreferences::setShowTapPreview,
        )
    }
}

@Composable
private fun CapitalizationToggleSection(layoutPreferences: LayoutPreferences) {
    val settings by layoutPreferences.settings.collectAsState()
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

/** The one manual, opt-in network call in the whole app — see [dev.omakey.core.update.UpdateChecker]'s
 * own doc and the privacy notice above it. A plain button + inline status text, not a background
 * check: nothing happens until the user taps it, every single time. */
@Composable
private fun UpdateCheckRow() {
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

/** Normal vs. Grid — orthogonal to the color theme picked via [ThemePicker] below (every color
 * theme works with either layout mode, see [dev.omakey.core.theme.LayoutMode]'s doc), shown
 * first in Appearance since it decides how the rest of the section's options apply (e.g. "Key
 * backgrounds" is a Normal-mode-only concept). Material3's own segmented-button pill row — the
 * platform's standard two/three-way switch control — rather than a bespoke bordered-box list, and
 * with no checkmark icon (`icon = {}` suppresses `SegmentedButton`'s default one): the pill's own
 * filled/outlined state already communicates which option is selected. */
@Composable
private fun LayoutModePicker(themeRepository: ThemeRepository) {
    val currentMode by themeRepository.layoutMode.collectAsState()
    val options = listOf(
        dev.omakey.core.theme.LayoutMode.NORMAL to "Normal",
        dev.omakey.core.theme.LayoutMode.GRID to "Grid",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = mode == currentMode,
                onClick = { themeRepository.setLayoutMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
                label = { Text(label) },
            )
        }
    }
}

/** Split into two sub-sections, per real user feedback: a custom theme built while previewing one
 * layout mode often has fields (`gridBorderColor`/`gridBorderWidth`) that were never actually
 * looked at for the *other* mode, so it "doesn't always work" there — not broken, just half-tuned.
 * The 4 built-in presets aren't affected by that (they're tuned for both modes already), so they
 * stay a flat always-visible picker; only the custom-theme list below them filters by
 * [layoutMode]. */
@Composable
private fun ThemePicker(
    themeRepository: ThemeRepository,
    customThemePreferences: CustomThemePreferences,
    layoutMode: dev.omakey.core.theme.LayoutMode,
    onCreateTheme: () -> Unit,
    onEditTheme: (OmakeyTheme) -> Unit,
) {
    val currentTheme by themeRepository.currentTheme.collectAsState()
    val customThemes by customThemePreferences.themes.collectAsState()
    var themeToDelete by remember { mutableStateOf<OmakeyTheme?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Presets — a flat segmented toggle, same control style as Layout style above, since
        // there are always exactly these 4 and exactly one is ever selected.
        val presetOptions = listOf(
            Presets.Light to "Light",
            Presets.Dark to "Dark",
            Presets.Auto to "Auto",
            Presets.Accent to "Accent",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            presetOptions.forEachIndexed { index, (preset, label) ->
                SegmentedButton(
                    selected = preset.id == currentTheme.id,
                    onClick = { themeRepository.setTheme(preset) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = presetOptions.size),
                    icon = {},
                    label = { Text(label) },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(text = "Custom themes", style = MaterialTheme.typography.bodyLarge)

        // null designedForLayoutMode = made before this field existed, or never re-tagged —
        // shown regardless of mode rather than disappearing from a list it used to be in.
        val visibleCustomThemes = customThemes.filter { it.designedForLayoutMode == null || it.designedForLayoutMode == layoutMode }
        if (visibleCustomThemes.isEmpty() && customThemes.isNotEmpty()) {
            Text(
                text = "You have custom themes, but none are tagged for ${if (layoutMode == dev.omakey.core.theme.LayoutMode.GRID) "Grid" else "Normal"} mode yet — edit one to tag it, or create a new one below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        visibleCustomThemes.forEach { theme ->
            ThemeRow(
                theme = theme,
                selected = theme.id == currentTheme.id,
                onClick = { themeRepository.setTheme(theme) },
                onEdit = { onEditTheme(theme) },
                onDelete = { themeToDelete = theme },
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
private fun ThemeEditorOverlay(
    initialTheme: OmakeyTheme?,
    layoutMode: dev.omakey.core.theme.LayoutMode,
    layoutPreferences: LayoutPreferences,
    onSave: (OmakeyTheme) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // "Key color" (below) only has anything to actually paint in Normal mode if key backgrounds
    // are turned on at all — LayoutSettings.showKeyBackgrounds, the same "Key backgrounds"
    // Appearance toggle, is what gates Color.Transparent vs. theme.keyBackground in KeyRowView.
    // Real user feedback/bug report: picking a Key color here appeared to do nothing at all,
    // because that toggle lives in a separate Appearance section the user had no reason to
    // associate with the color picker they were looking at — and the preview below used to
    // hardcode showKeyBackgrounds = false regardless, so it never would have shown the color even
    // if the toggle *was* already on elsewhere. Surfacing the toggle right next to the color it
    // controls (Normal-mode-only, same as "Key color" itself) fixes both: the setting is
    // discoverable from the one place it actually matters, and the live preview reflects it.
    val layoutSettings by layoutPreferences.settings.collectAsState()
    // Name is entered at save time now (see the "Save theme" button's onClick and the dialog
    // below), not as an always-visible field here — real user feedback: the name doesn't affect
    // anything about the preview above it, so it was just taking up space in a screen that's
    // otherwise entirely about color, for a field most people would leave on its default anyway.
    // Still tracked here (not purely local to the dialog) so re-opening the editor for an existing
    // custom theme pre-fills the dialog with its current name rather than "My theme" every time.
    var name by remember { mutableStateOf(initialTheme?.name ?: "My theme") }
    var showSaveNamePrompt by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(initialTheme?.keyboardBackground?.toComposeColor() ?: Color(0xFF1E1E1E)) }
    var keyColor by remember { mutableStateOf(initialTheme?.keyBackground?.toComposeColor() ?: Color(0xFF2C2C2C)) }
    var stripeColor by remember { mutableStateOf(initialTheme?.middleRowStripeColor?.toComposeColor() ?: Color(0xFF3A3A3A)) }
    var spacebarColor by remember { mutableStateOf(initialTheme?.spacebarAccentColor?.toComposeColor() ?: Color(0xFF4A90D9)) }
    // Grid mode's own border color — independent of the auto-derived isDark default (see
    // OmakeyTheme.gridBorderColor's doc) once the user has actually edited it here.
    var gridBorderColor by remember {
        mutableStateOf(
            initialTheme?.gridBorderColor?.toComposeColor()
                ?: (if (relativeLuminance(keyColor) < 0.5f) Color(0xFFE0E0E0) else Color(0xFF2A2A2A)),
        )
    }
    var gridBorderWidth by remember {
        mutableStateOf(initialTheme?.gridBorderWidth ?: dev.omakey.core.theme.GridBorderWidth.MD)
    }

    val previewTheme = remember(name, background, keyColor, stripeColor, spacebarColor, gridBorderColor, gridBorderWidth) {
        buildCustomTheme(
            id = initialTheme?.id ?: (CustomThemePreferences.ID_PREFIX + java.util.UUID.randomUUID().toString()),
            name = name.ifBlank { "My theme" },
            backgroundColor = background,
            keyColor = keyColor,
            stripeColor = stripeColor,
            spacebarColor = spacebarColor,
            gridBorderColor = gridBorderColor,
            gridBorderWidth = gridBorderWidth,
            // Tagged with whichever mode is actually being previewed/edited right now — not
            // preserved from initialTheme, since re-saving a theme while looking at a *different*
            // mode than it was originally made for should re-tag it to the mode actually being
            // edited (that's the whole point: the fields being edited right now are for this mode).
            designedForLayoutMode = layoutMode,
        )
    }

    // The 5 edit fields, one page each — see [ThemeEditCarousel]'s own doc for why this replaced
    // the old vertically-stacked list of always-expanded pickers.
    // "Key color" only visually matters in Normal mode — every Grid-mode cell fills with
    // Background instead (see KeyRowView's own doc on why: one less setting doing the same
    // visual job as Background, since every cell is "boxed" by its border regardless of key type
    // there). Hidden from the carousel while editing with Grid mode active, rather than shown but
    // silently doing nothing — the underlying `keyColor` state (and the theme's derived
    // `keyTextColor`/`keyBackgroundPressed`/etc, still computed from it) is untouched, so
    // switching back to Normal mode later still has whatever was last set.
    val editPages = buildList {
        add(ThemeEditField("Background", background) { background = it })
        if (layoutMode != dev.omakey.core.theme.LayoutMode.GRID) {
            add(ThemeEditField("Key color", keyColor) { keyColor = it })
        }
        add(ThemeEditField("Home-row stripe", stripeColor) { stripeColor = it })
        add(ThemeEditField("Spacebar", spacebarColor) { spacebarColor = it })
        // Grid-mode-only, same reasoning as hiding "Key color" above (just the reverse) — this
        // field has no visible effect while editing/previewing Normal mode, so showing it there
        // read as a control that silently does nothing (real user feedback).
        if (layoutMode == dev.omakey.core.theme.LayoutMode.GRID) {
            add(ThemeEditField("Grid border", gridBorderColor) { gridBorderColor = it })
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
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

            // Sticky, full-keyboard preview — deliberately outside any scroll container so it
            // stays on screen the whole time the carousel below is being swiped/edited, instead of
            // scrolling away with the rest of the form (real user feedback: "so we know how it
            // looks as we edit"). Fixed height, everything below shares the remaining space. No
            // "Preview" label above it (removed, real user feedback) — self-evident from context.
            // Provides the user's actual current layout mode (real bug, fixed: this preview used
            // to always render Normal-mode keys — LocalKeyboardLayoutMode defaults to NORMAL when
            // nothing provides it — even while editing a theme with Grid mode active) so the
            // preview matches what the keyboard will really look like.
            androidx.compose.runtime.CompositionLocalProvider(
                dev.omakey.core.theme.LocalKeyboardLayoutMode provides layoutMode,
            ) {
                // Real bug, fixed: this preview used to hardcode showKeyBackgrounds = false
                // regardless of the actual setting, so editing "Key color" for a Normal-mode theme
                // never showed any visible change here even once the toggle below was turned on.
                ThemePreviewMock(previewTheme, showKeyBackgrounds = layoutSettings.showKeyBackgrounds)
            }

            // "Key color" (below) is otherwise invisible in Normal mode — KeyRowView renders keys
            // fully transparent there unless key backgrounds are turned on (the flat/borderless
            // look is the Normal-mode default, matching Fleksy). Surfaced right here, next to the
            // color it gates, instead of leaving it to be found separately under Appearance —
            // Grid mode always shows its own bordered cells regardless of this toggle, so it's
            // Normal-mode-only, same as "Key color" itself.
            if (layoutMode != dev.omakey.core.theme.LayoutMode.GRID) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Show key backgrounds", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = layoutSettings.showKeyBackgrounds,
                        onCheckedChange = layoutPreferences::setShowKeyBackgrounds,
                    )
                }
            }

            // Border thickness — a 3-step preset, not a color, so it doesn't fit ThemeEditField's
            // color-carousel model. Grid-mode-only, same reasoning as hiding "Key color" above.
            if (layoutMode == dev.omakey.core.theme.LayoutMode.GRID) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "Border thickness", style = MaterialTheme.typography.bodyLarge)
                    val widthOptions = listOf(
                        dev.omakey.core.theme.GridBorderWidth.SM to "SM",
                        dev.omakey.core.theme.GridBorderWidth.MD to "MD",
                        dev.omakey.core.theme.GridBorderWidth.LG to "LG",
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        widthOptions.forEachIndexed { index, (width, label) ->
                            SegmentedButton(
                                selected = width == gridBorderWidth,
                                onClick = { gridBorderWidth = width },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = widthOptions.size),
                                icon = {},
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            ThemeEditCarousel(
                pages = editPages,
                modifier = Modifier.weight(1f),
            )

            Button(onClick = { showSaveNamePrompt = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Save theme")
            }
        }
    }

    if (showSaveNamePrompt) {
        ThemeSaveNamePrompt(
            initialName = name,
            onConfirm = { finalName ->
                name = finalName
                onSave(previewTheme.copy(name = finalName.ifBlank { "My theme" }))
            },
            onDismiss = { showSaveNamePrompt = false },
        )
    }
}

/** Shown only when "Save theme" is tapped — see [ThemeEditorOverlay]'s own doc for why the name
 * field moved here instead of sitting inline in the editor the whole time. */
@Composable
private fun ThemeSaveNamePrompt(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameInput by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Name this theme") },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text(text = "Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(nameInput) }) { Text(text = "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "Cancel") }
        },
    )
}

/** A real, full `KeyboardLayout` rendered row-by-row via `KeyRowView` (same composable the actual
 * keyboard renders, reused rather than a bespoke mock — same trick as
 * `KeyboardSizePositionOverlay`), plus a static sample of the extension bar (suggestion/emoji
 * chips) on top via [dev.omakey.app.keyboard.ui.SuggestionsTabContent] — real user feedback: the
 * "Show key backgrounds" toggle only affected the letter grid here, so there was no way to preview
 * its effect on the suggestion bar's own chips while editing a theme. A real `TopStrip`/
 * `KeyboardViewModel` would need a live `TextEditor`/prediction engine/undo state Settings has no
 * business constructing just for a preview — sample suggestions/emoji and no-op callbacks get the
 * same pixel-accurate chip styling without any of that. Row height trimmed from 52dp to 44dp
 * (real user feedback, "decrease the keyboard height a bit") — matters more now that the bar above
 * adds its own height on top. */
@Composable
private fun ThemePreviewMock(theme: OmakeyTheme, showKeyBackgrounds: Boolean = false) {
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }
    val rowHeightDp = 44
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.keyboardBackground.toComposeColor(), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(if (isGridMode) theme.keyboardBackground.toComposeColor() else theme.suggestionBarBackground.toComposeColor()),
        ) {
            dev.omakey.app.keyboard.ui.SuggestionsTabContent(
                suggestions = listOf("hello", "world"),
                emojiSuggestions = listOf("😊"),
                firstSuggestionKind = dev.omakey.app.keyboard.SuggestionKind.PLAIN,
                activeSuggestionIndex = -1,
                theme = theme,
                fontFamily = null,
                showKeyBackgrounds = showKeyBackgrounds,
                onAccept = {},
                onAcceptEmoji = {},
            )
        }
        Layouts.QwertyEnUS.rows.forEachIndexed { rowIndex, row ->
            dev.omakey.app.keyboard.ui.KeyRowView(
                rowKeys = row.keys,
                rowHeightDp = rowHeightDp,
                shiftOn = false,
                theme = theme,
                accessibleMode = false,
                showKeyBackgrounds = showKeyBackgrounds,
                // Matches KeyGrid's own homeRowIndex for QwertyEnUS (see KeyboardRoot.kt) — the
                // ASDFGHJKL row (index 1), not the ZXCVBNM/shift row (real bug, fixed: this
                // preview had it one row too low).
                isHomeRow = rowIndex == 1,
                onKeyTap = {},
                ancestorCoordinates = noOpAncestor,
                onBoundsMeasured = {},
            )
        }
    }
}

/** One page of [ThemeEditCarousel] — a single color field being edited. */
private data class ThemeEditField(val label: String, val color: Color, val onColorChange: (Color) -> Unit)

/** Swipeable, one-field-at-a-time carousel for the theme editor's 4 color pickers (real user
 * feedback: the old always-expanded vertical stack of 4 pickers competed for space and pushed the
 * preview off-screen while editing). Page dots sit *below* the pager, right under the hex/copy
 * row — real user feedback: dots above the pager (this composable's first version) read as "the
 * keyboard preview itself is swipable," not "these 4 fields are." No separate Prev/Next buttons
 * (removed, real user feedback) — the dots alone already communicate "there are 4 pages here,"
 * and swipe is the only way to move between them now. */
@Composable
private fun ThemeEditCarousel(pages: List<ThemeEditField>, modifier: Modifier = Modifier) {
    val fields = pages
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { fields.size })

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val field = fields[page]
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                // Centers both the label row and the HSV picker as a block — previously left-
                // aligned, which stranded the picker in a strip down the left edge with a lot of
                // dead space to its right (real user feedback, screenshot-confirmed).
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(field.color, RoundedCornerShape(6.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    )
                    Text(text = field.label, style = MaterialTheme.typography.titleSmall)
                }
                HsvColorPicker(color = field.color, onColorChange = field.onColorChange)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            fields.indices.forEach { index ->
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                        .background(
                            if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
            }
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
    // Free-typed text, not derived straight from hue/saturation/value on every recomposition —
    // that would fight the user mid-keystroke (e.g. re-normalizing "#12" to "#000012" before
    // they've finished typing the other 4 digits). Instead this is only ever written to
    // programmatically from [emit] (square/strip drag) or [applyHex] (a hex string that actually
    // parsed) — user keystrokes that don't yet form a valid 6-digit hex just sit here untouched,
    // not reverted or rejected, until they either complete a valid color or navigate away.
    var hexText by remember { mutableStateOf(colorToHexString(color)) }

    fun emit() {
        val newColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
        hexText = colorToHexString(newColor)
        onColorChange(newColor)
    }

    fun applyHex(input: String) {
        hexText = input
        val hex = input.removePrefix("#").trim()
        if (hex.length != 6 || hex.any { it.lowercaseChar() !in "0123456789abcdef" }) return
        val parsed = Color(android.graphics.Color.parseColor("#$hex"))
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(parsed.toArgb(), out)
        hue = out[0]
        saturation = out[1]
        value = out[2]
        onColorChange(parsed)
    }

    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val clipboardManager = LocalClipboardManager.current

    // fillMaxWidth + centered here (real bug, fixed — screenshot report: picker rendered
    // hugging the left edge, hex field's text invisible and its box visibly squeezed short).
    // Root cause: this Column previously had no fillMaxWidth, so it sized itself to *wrap
    // content* — which means measuring each child's own "intrinsic" preferred width. A Row
    // containing a `weight()` child (the hex row below) has no well-defined intrinsic width
    // (weight-based sizing only resolves once real bounded layout constraints are known), so the
    // wrap-content pass effectively ignored the text field's actual space needs, undersizing the
    // whole Column and squeezing the hex row into whatever tiny width was left over — which also
    // explains why centering the *block* (in the caller, ThemeEditCarousel) looked like it wasn't
    // taking effect: the block itself had been measured far narrower than intended. fillMaxWidth
    // here sidesteps intrinsic measurement entirely (no guessing, just "take what's offered"),
    // and the picker Row (still not fillMaxWidth, still its own fixed ~200dp content width) is
    // then genuinely centered *within* this Column by horizontalAlignment.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = hexText,
                onValueChange = { applyHex(it) },
                label = { Text(text = "Hex") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboardManager.setText(AnnotatedString(hexText)) }) {
                Icon(imageVector = PhosphorCopy, contentDescription = "Copy hex code")
            }
        }
    }
}

private fun colorToHexString(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

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
    gridBorderColor: Color,
    gridBorderWidth: dev.omakey.core.theme.GridBorderWidth,
    designedForLayoutMode: dev.omakey.core.theme.LayoutMode,
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
        gridBorderColor = gridBorderColor.toColorSpec(),
        gridBorderWidth = gridBorderWidth,
        designedForLayoutMode = designedForLayoutMode,
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
