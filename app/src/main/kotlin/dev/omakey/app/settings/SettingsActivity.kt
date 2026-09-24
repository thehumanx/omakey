package dev.omakey.app.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.R
import dev.omakey.app.keyboard.VibratorKeyboardFeedback
import dev.omakey.app.keyboard.ui.FontCatalog
import dev.omakey.core.clipboard.ClipboardHistoryStore
import dev.omakey.core.clipboard.ClipboardPreferences
import dev.omakey.core.db.OmakeyDatabase
import dev.omakey.core.db.WordDao
import dev.omakey.core.emoji.EmojiSkinTonePreferences
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.IncognitoPreferences
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.theme.AccessibilityPreferences
import dev.omakey.core.theme.CustomThemePreferences
import dev.omakey.core.theme.FontPreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.ThemeRepository
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
        val emojiSkinTonePreferences = EmojiSkinTonePreferences(applicationContext)
        val updatePreferences = dev.omakey.core.update.UpdatePreferences(applicationContext)
        val feedback = VibratorKeyboardFeedback(applicationContext, hapticSoundPreferences)
        val database = OmakeyDatabase.getInstance(applicationContext)
        val wordDao = database.wordDao()
        val clipboardPreferences = ClipboardPreferences(applicationContext)
        val localePreferences = dev.omakey.core.locale.LocalePreferences(applicationContext)
        val languagePacks = dev.omakey.app.languages.LanguagePacks.get(applicationContext)
        val clipboardDao = database.clipboardDao()
        // Same store the IME uses, so "clear history" removes the image files too rather than
        // leaving them orphaned — the exact failure the trim path used to have.
        val clipboardHistory = ClipboardHistoryStore(applicationContext, clipboardDao)
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
                        emojiSkinTonePreferences = emojiSkinTonePreferences,
                        updatePreferences = updatePreferences,
                        wordDao = wordDao,
                        clipboardPreferences = clipboardPreferences,
                        clipboardDao = clipboardDao,
                        clipboardHistory = clipboardHistory,
                        localePreferences = localePreferences,
                        languagePacks = languagePacks,
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
    emojiSkinTonePreferences: EmojiSkinTonePreferences,
    updatePreferences: dev.omakey.core.update.UpdatePreferences,
    wordDao: WordDao,
    clipboardPreferences: ClipboardPreferences,
    clipboardDao: dev.omakey.core.db.ClipboardDao,
    clipboardHistory: ClipboardHistoryStore,
    localePreferences: dev.omakey.core.locale.LocalePreferences,
    languagePacks: dev.omakey.app.languages.LanguagePacks,
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
    // The keyboard preview renders in the chosen font, so picking one shows what it looks like on
    // actual keycaps rather than only in the picker's own label.
    val previewFontFamily = remember(currentFontId) { dev.omakey.app.keyboard.ui.FontCatalog.resolve(currentFontId) }
    val layoutSettings by layoutPreferences.settings.collectAsState()
    // Collected once here and passed down. Each of these used to be collected again inside every
    // toggle that read it — five separate collectors on layoutPreferences.settings and three on
    // autocorrectPreferences.settings, so touching any one field recomposed all of them.
    val autocorrectSettings by autocorrectPreferences.settings.collectAsState()
    var showTestOverlay by remember { mutableStateOf(false) }
    var showLearnedWordsOverlay by remember { mutableStateOf(false) }
    var showClipboardHistoryOverlay by remember { mutableStateOf(false) }
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
                    // Directly under the picker, and fed the *resolved* theme plus every live
                    // appearance setting — layout style, theme, font, key backgrounds, home-row
                    // highlight, capitalization and edge padding all land here. Normal vs. Grid is
                    // the difference this exists for: it is a structural change to how every key
                    // is drawn, and a two-word segmented button conveys none of it.
                    Spacer(Modifier.height(4.dp))
                    // Must provide the layout mode: LocalKeyboardLayoutMode defaults to NORMAL
                    // when nothing supplies it, so without this the preview would render Normal
                    // keys even with Grid selected — which is the single thing this preview most
                    // needs to show. Same trap the theme editor's own preview already hit once.
                    androidx.compose.runtime.CompositionLocalProvider(
                        dev.omakey.core.theme.LocalKeyboardLayoutMode provides layoutMode,
                    ) {
                        ThemePreviewMock(
                            theme = effectiveTheme,
                            showKeyBackgrounds = layoutSettings.showKeyBackgrounds,
                            fontFamily = previewFontFamily,
                            homeRowTinted = layoutSettings.showMiddleRowStripe,
                            alwaysShowUppercaseLetters = layoutSettings.alwaysShowUppercaseLetters,
                            edgePadding = layoutSettings.edgePadding,
                        )
                    }
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
                                "spacebar, pressed keys and caps lock instead of the theme's own. " +
                                "The Accent theme already uses your device's colors throughout.",
                            checked = useSystemAccent,
                            onCheckedChange = themeRepository::setUseSystemAccent,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AppearanceLayoutToggles(layoutSettings, layoutPreferences)
                    CapitalizationToggleSection(layoutSettings, layoutPreferences)
                    EdgePaddingToggle(layoutSettings, layoutPreferences)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ClickableSettingRow(
                        title = "Keyboard size & position",
                        description = "Resize the keyboard and raise it off the bottom edge for " +
                            "easier one-handed thumb reach — drag to adjust both.",
                        onClick = { showSizePositionOverlay = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(text = "Font", style = MaterialTheme.typography.bodyLarge)
                    FontPicker(fontPreferences, currentFontId)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EmojiSkinTonePicker(emojiSkinTonePreferences)
                }
            }

            item {
                SettingsSection(title = "Languages") {
                    LanguagesSection(localePreferences, languagePacks)
                }
            }

            item {
                SettingsSection(title = "Typing") {
                    AutocorrectToggle(autocorrectSettings, autocorrectPreferences)
                    AutoCapitalizeToggle(autocorrectSettings, autocorrectPreferences)
                    DoubleTapSpaceForPeriodToggle(autocorrectSettings, autocorrectPreferences)
                    NextWordPredictionToggle(predictionPreferences)
                    ImplicitLearningToggle(incognitoPreferences)
                    TapPreviewToggle(layoutSettings, layoutPreferences)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ClickableSettingRow(
                        title = "Learned words",
                        description = "Words your own typing has taught the keyboard — view, " +
                            "search, or remove any that shouldn't have been learned.",
                        onClick = { showLearnedWordsOverlay = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ClipboardHistoryToggle(clipboardPreferences)
                    ClickableSettingRow(
                        title = "Clipboard history",
                        description = "Everything the keyboard has saved from your clipboard — " +
                            "view it, unpin or remove single entries, or clear the lot.",
                        onClick = { showClipboardHistoryOverlay = true },
                    )
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
                        // Build number alongside the version name, because they can legitimately
                        // disagree: versionName is deliberately held fixed across iterative dev
                        // installs within a release cycle (see app/build.gradle.kts) while
                        // versionCode increments every time. The update check can only compare
                        // versionName against a release tag, so on a dev build "you're up to date"
                        // means "no newer *tag* exists", not "you are running the tagged code".
                        // Showing the build number is what lets someone tell which they have.
                        text = "Version ${dev.omakey.app.BuildConfig.VERSION_NAME} " +
                            "(build ${dev.omakey.app.BuildConfig.VERSION_CODE})",
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
    if (showClipboardHistoryOverlay) {
        ClipboardHistoryOverlay(
            clipboardDao = clipboardDao,
            clipboardHistory = clipboardHistory,
            onClose = { showClipboardHistoryOverlay = false },
        )
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
