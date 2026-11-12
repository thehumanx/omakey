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
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
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
import kotlinx.coroutines.flow.first
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
        val topStripPreferences = dev.omakey.app.keyboard.TopStripTabPreferences(applicationContext)
        val updatePreferences = dev.omakey.core.update.UpdatePreferences(applicationContext)
        val feedback = VibratorKeyboardFeedback(applicationContext, hapticSoundPreferences)
        // Each Activity instance owns its own SoundPool; free it with the Activity (rotation
        // recreates it) rather than leaking one per instance.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = feedback.release()
        })
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
                        topStripPreferences = topStripPreferences,
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
    // Every role, not just the handful this screen was thought to use. Anything left unset falls
    // back to Material's stock purple, and a lot of widgets reach for roles nobody expects:
    // segmented buttons use secondaryContainer, dialogs and menus the surfaceContainer ramp, tonal
    // elevation surfaceTint, switches outline. Those were the purple still showing after the
    // earlier partial fix.
    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFD6D6D6),
            onPrimary = Color(0xFF1C1C1C),
            primaryContainer = Color(0xFF3A3A3A),
            onPrimaryContainer = Color(0xFFEDEDED),
            inversePrimary = Color(0xFF5F5F5F),
            secondary = Color(0xFFB8B8B8),
            onSecondary = Color(0xFF1C1C1C),
            secondaryContainer = Color(0xFF3A3A3A),
            onSecondaryContainer = Color(0xFFEDEDED),
            tertiary = Color(0xFFB8B8B8),
            onTertiary = Color(0xFF1C1C1C),
            tertiaryContainer = Color(0xFF3A3A3A),
            onTertiaryContainer = Color(0xFFEDEDED),
            background = Color(0xFF0F0F0F),
            onBackground = Color(0xFFE8E8E8),
            surface = Color(0xFF141414),
            onSurface = Color(0xFFE8E8E8),
            surfaceVariant = Color(0xFF232323),
            onSurfaceVariant = Color(0xFFC4C4C4),
            surfaceTint = Color(0xFF8A8A8A),
            inverseSurface = Color(0xFFE8E8E8),
            inverseOnSurface = Color(0xFF1C1C1C),
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFF3A3A3A),
            scrim = Color(0xFF000000),
            surfaceBright = Color(0xFF2E2E2E),
            surfaceDim = Color(0xFF0F0F0F),
            surfaceContainerLowest = Color(0xFF0A0A0A),
            surfaceContainerLow = Color(0xFF1A1A1A),
            surfaceContainer = Color(0xFF1E1E1E),
            surfaceContainerHigh = Color(0xFF262626),
            surfaceContainerHighest = Color(0xFF303030),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF3C3C3C),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE2E2E2),
            onPrimaryContainer = Color(0xFF1C1C1C),
            inversePrimary = Color(0xFFD0D0D0),
            secondary = Color(0xFF5F5F5F),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE2E2E2),
            onSecondaryContainer = Color(0xFF1C1C1C),
            tertiary = Color(0xFF5F5F5F),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE2E2E2),
            onTertiaryContainer = Color(0xFF1C1C1C),
            background = Color(0xFFFAFAFA),
            onBackground = Color(0xFF1C1C1C),
            surface = Color(0xFFFDFDFD),
            onSurface = Color(0xFF1C1C1C),
            surfaceVariant = Color(0xFFF0F0F0),
            onSurfaceVariant = Color(0xFF474747),
            surfaceTint = Color(0xFF7A7A7A),
            inverseSurface = Color(0xFF2E2E2E),
            inverseOnSurface = Color(0xFFF2F2F2),
            outline = Color(0xFF7A7A7A),
            outlineVariant = Color(0xFFD4D4D4),
            scrim = Color(0xFF000000),
            surfaceBright = Color(0xFFFDFDFD),
            surfaceDim = Color(0xFFDCDCDC),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF7F7F7),
            surfaceContainer = Color(0xFFF1F1F1),
            surfaceContainerHigh = Color(0xFFEBEBEB),
            surfaceContainerHighest = Color(0xFFE5E5E5),
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
    topStripPreferences: dev.omakey.app.keyboard.TopStripTabPreferences,
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

    // Where the user is: an open page over an optional search (see SettingsNav). Saveable so
    // rotating the phone keeps the page, the search and its query.
    var nav by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = SettingsNavSaver) { mutableStateOf(SettingsNav()) }
    // The setting a search result just opened. Flashed for a moment, then cleared; deliberately
    // not saveable, a flash replayed after rotation would only confuse.
    var highlight by remember { mutableStateOf<SettingKey?>(null) }
    // Bumped on every result tap, so opening the same result again restarts the timer.
    var highlightToken by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(highlightToken) {
        if (highlight != null) {
            kotlinx.coroutines.delay(HIGHLIGHT_MILLIS)
            highlight = null
        }
    }
    // Held here, above the page switch, so the home list's scroll position survives opening a page
    // and coming back. Inside SettingsHome it was created fresh each time the list re-entered
    // composition, so Back always landed at the top. Saveable for rotation too.
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Same for the search results, so Back from a result lands where the user left the list. A
    // new query starts it at the top again.
    val searchListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val back: () -> Unit = {
        highlight = null
        nav = nav.back() ?: SettingsNav()
    }
    androidx.activity.compose.BackHandler(enabled = nav.back() != null) { back() }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalSettingHighlight provides highlight) {
            when (val page = nav.page) {
                null -> if (nav.searchOpen) {
                    SettingsSearchScreen(
                        query = nav.query,
                        onQueryChange = {
                            nav = nav.withQuery(it)
                            scope.launch { searchListState.scrollToItem(0) }
                        },
                        listState = searchListState,
                        onBack = back,
                        onOpen = { key ->
                            nav = nav.openResult(key)
                            highlight = key
                            highlightToken++
                        },
                    )
                } else {
                    SettingsHome(
                        listState = homeListState,
                        onOpen = { nav = nav.openPage(it) },
                        onSearch = { nav = nav.openSearch() },
                        onOpenSystemSettings = onOpenSystemSettings,
                        onSwitchKeyboard = onSwitchKeyboard,
                    )
                }

                else -> SettingsSubPage(
                    page = page,
                    onBack = back,
                    scrollTo = highlight?.section?.takeIf { it.page == page },
                    preview = if (page != SettingsPage.APPEARANCE) null else {
                        {
                            // First, and fed the *resolved* theme plus every live appearance setting, so
                            // each change below shows up here immediately. Normal vs. Grid is the change
                            // it matters most for: a structural difference two words can't convey.
                            // Must provide the layout mode — LocalKeyboardLayoutMode defaults to NORMAL.
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
                                    showSecondarySymbols = layoutSettings.secondarySymbolsShown,
                                    fleksySymbols = layoutSettings.fleksySymbols,
                                    interactive = true,
                                )
                            }
                        }
                    },
                ) { section ->
                    when (section) {
                        SettingsSectionKey.LAYOUT_STYLE -> LayoutModePicker(themeRepository)
                        SettingsSectionKey.THEME -> {
                            ThemePicker(
                                themeRepository = themeRepository,
                                customThemePreferences = customThemePreferences,
                                layoutMode = layoutMode,
                                onCreateTheme = { themeBeingEdited = null; showThemeEditor = true },
                                onEditTheme = { theme -> themeBeingEdited = theme; showThemeEditor = true },
                            )
                            if (Build.VERSION.SDK_INT >= SettingKey.SYSTEM_ACCENT.minSdk) {
                                SettingToggle(
                                    key = SettingKey.SYSTEM_ACCENT,
                                    description = "Use your device's Material You accent for the " +
                                        "spacebar, caps lock and active buttons instead of the theme's " +
                                        "own. The Accent theme already uses your device's colors throughout.",
                                    checked = useSystemAccent,
                                    onCheckedChange = themeRepository::setUseSystemAccent,
                                )
                            }
                        }
                        SettingsSectionKey.KEYS -> {
                            AppearanceLayoutToggles(layoutSettings, layoutPreferences)
                            SecondarySymbolsToggle(layoutSettings, layoutPreferences)
                            CapitalizationToggleSection(layoutSettings, layoutPreferences)
                        }
                        SettingsSectionKey.FONT -> FontPicker(fontPreferences, currentFontId)
                        SettingsSectionKey.SIZE_POSITION -> {
                            ClickableSettingRow(
                                key = SettingKey.SIZE_POSITION,
                                description = "Resize the keyboard and raise it off the bottom edge for " +
                                    "easier one-handed thumb reach — drag to adjust both.",
                                onClick = { showSizePositionOverlay = true },
                            )
                            EdgePaddingToggle(layoutSettings, layoutPreferences)
                        }
                        SettingsSectionKey.SUGGESTION_BAR -> TopStripPagesSection(topStripPreferences)
                        SettingsSectionKey.BUTTONS -> {
                            SwapEmojiLanguageToggle(layoutSettings, layoutPreferences)
                            TapPreviewToggle(layoutSettings, layoutPreferences)
                        }
                        SettingsSectionKey.SYMBOLS -> {
                            FleksySymbolsToggle(layoutSettings, layoutPreferences)
                            PopupSymbolsField(layoutSettings, layoutPreferences)
                        }
                        SettingsSectionKey.NUMBER_PAD -> NumberPadSection(layoutSettings, layoutPreferences)
                        SettingsSectionKey.EMOJI -> EmojiSkinTonePicker(emojiSkinTonePreferences)
                        SettingsSectionKey.LANGUAGES -> LanguagesSection(localePreferences, languagePacks)
                        SettingsSectionKey.CORRECTIONS -> {
                            AutocorrectToggle(autocorrectSettings, autocorrectPreferences)
                            NextWordPredictionToggle(predictionPreferences)
                        }
                        SettingsSectionKey.PUNCTUATION -> {
                            AutoCapitalizeToggle(autocorrectSettings, autocorrectPreferences)
                            DoubleTapSpaceForPeriodToggle(autocorrectSettings, autocorrectPreferences)
                            AutoSpaceAfterPunctuationToggle(autocorrectSettings, autocorrectPreferences)
                        }
                        SettingsSectionKey.GESTURES -> GestureSettingsSection(gesturePreferences)
                        SettingsSectionKey.LEARNING -> {
                            ImplicitLearningToggle(incognitoPreferences)
                            ClickableSettingRow(
                                key = SettingKey.LEARNED_WORDS,
                                description = "Words your own typing has taught the keyboard — view, " +
                                    "search, or remove any that shouldn't have been learned.",
                                onClick = { showLearnedWordsOverlay = true },
                            )
                        }
                        SettingsSectionKey.CLIPBOARD -> {
                            ClipboardHistoryToggle(clipboardPreferences)
                            ClickableSettingRow(
                                key = SettingKey.CLIPBOARD_HISTORY,
                                description = "Everything the keyboard has saved from your clipboard — " +
                                    "view it, unpin or remove single entries, or clear the lot.",
                                onClick = { showClipboardHistoryOverlay = true },
                            )
                        }
                        SettingsSectionKey.WHAT_LEAVES ->
                            Text(text = stringResource(R.string.privacy_notice), style = MaterialTheme.typography.bodyMedium)
                        SettingsSectionKey.SOUND_HAPTICS -> FeedbackSettingsSection(hapticSoundPreferences, feedback)
                        SettingsSectionKey.ACCESSIBILITY -> AccessibleModeToggle(accessibilityPreferences)
                        SettingsSectionKey.UPDATES -> {
                            Text(
                                // Build number alongside the version name, because they can legitimately
                                // disagree: versionName is held fixed across dev installs within a release
                                // cycle while versionCode increments every time, so on a dev build "up to
                                // date" means "no newer tag", not "running the tagged code".
                                text = "Version ${dev.omakey.app.BuildConfig.VERSION_NAME} " +
                                    "(build ${dev.omakey.app.BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val autoCheckEnabled by updatePreferences.settings.collectAsState()
                            Row(
                                modifier = rememberSettingHighlight(SettingKey.AUTO_UPDATE_CHECKS).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(text = SettingKey.AUTO_UPDATE_CHECKS.title, style = MaterialTheme.typography.bodyLarge)
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
            }
        }

        // Hidden while searching: it would sit over the results and the on-screen keyboard.
        if (!(nav.searchOpen && nav.page == null)) {
            FloatingActionButton(
                onClick = { showTestOverlay = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(24.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = dev.omakey.core.icons.PhosphorLineKeyboard,
                    contentDescription = "Try the keyboard",
                    modifier = Modifier.size(24.dp),
                )
            }
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

@Composable
private fun SettingsHome(
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (SettingsPage) -> Unit,
    onSearch: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onSwitchKeyboard: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.IconButton(onClick = onSearch) {
                    androidx.compose.material3.Icon(
                        imageVector = dev.omakey.core.icons.PhosphorLineMagnifyingGlass,
                        contentDescription = "Search settings",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        item {
            SettingsSection(title = "Setup") {
                val (isEnabled, isDefault) = rememberSetupStatus()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SetupStatusIcon(done = isEnabled)
                    Button(onClick = onOpenSystemSettings, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(text = stringResource(R.string.settings_enable_keyboard))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SetupStatusIcon(done = isDefault)
                    Button(onClick = onSwitchKeyboard, modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(text = stringResource(R.string.settings_choose_keyboard))
                    }
                }
            }
        }
        val groups = listOf(
            "Look & feel" to listOf(SettingsPage.APPEARANCE, SettingsPage.KEYBOARD),
            "Typing" to listOf(SettingsPage.LANGUAGES, SettingsPage.TYPING, SettingsPage.GESTURES, SettingsPage.SOUND),
            "More" to listOf(SettingsPage.PRIVACY, SettingsPage.ACCESSIBILITY, SettingsPage.ABOUT),
        )
        groups.forEach { (title, pages) ->
            item {
                SettingsSection(title = title) {
                    pages.forEach { page ->
                        UnindexedClickableSettingRow(title = page.title, description = page.summary, onClick = { onOpen(page) })
                    }
                }
            }
        }
        // Room under the last card so the test-keyboard button doesn't cover it.
        item { Spacer(Modifier.height(72.dp)) }
    }
}

/** How long a setting opened from search stays highlighted. */
private const val HIGHLIGHT_MILLIS = 1500L

private val SettingsNavSaver = androidx.compose.runtime.saveable.listSaver<SettingsNav, Any?>(
    save = { listOf(it.page?.name, it.searchOpen, it.query) },
    restore = { SettingsNav((it[0] as String?)?.let(SettingsPage::valueOf), it[1] as Boolean, it[2] as String) },
)

/**
 * One Settings page: a back arrow and title over its own scrolling list of section cards, built
 * from [SettingsIndex.sectionsOf] so the order search scrolls by is the order on screen.
 * [preview] sits above the sections (Appearance's live keyboard). When [scrollTo] is set the list
 * scrolls that section into view; the row inside it then finishes the job (rememberSettingHighlight).
 */
@Composable
private fun SettingsSubPage(
    page: SettingsPage,
    onBack: () -> Unit,
    scrollTo: SettingsSectionKey?,
    preview: (@Composable () -> Unit)?,
    section: @Composable (SettingsSectionKey) -> Unit,
) {
    val sections = SettingsIndex.sectionsOf(page)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(scrollTo) {
        val position = sections.indexOf(scrollTo)
        if (position < 0) return@LaunchedEffect
        // + 1 for the title row, + 1 more when there's a preview above the sections.
        val index = position + 1 + (if (preview != null) 1 else 0)
        // Wait for the first layout, then only scroll when the card's top isn't already on screen,
        // so a page whose first card is the target keeps its title in view. The row itself then
        // brings its own line into view (rememberSettingHighlight), which covers rows low in a tall card.
        val layout = androidx.compose.runtime.snapshotFlow { listState.layoutInfo }
            .first { it.visibleItemsInfo.isNotEmpty() }
        val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val topShown = item != null && item.offset >= layout.viewportStartOffset &&
            item.offset < layout.viewportEndOffset
        if (!topShown) listState.animateScrollToItem(index)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                    androidx.compose.material3.Icon(
                        imageVector = dev.omakey.core.icons.PhosphorLineArrowLeft,
                        contentDescription = "Back",
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(text = page.title, style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (preview != null) item { preview() }
        sections.forEach { key ->
            item(key = key) { SettingsSection(key) { section(key) } }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}
