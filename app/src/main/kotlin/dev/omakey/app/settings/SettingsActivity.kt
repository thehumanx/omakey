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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.omakey.app.design.ButtonKind
import dev.omakey.app.design.Omakey
import dev.omakey.app.design.OmakeyButton
import dev.omakey.app.design.PageHeader
import dev.omakey.app.design.RowTrailing
import dev.omakey.app.design.SectionLabel
import dev.omakey.app.design.SettingsCard
import dev.omakey.app.design.SettingsRowLayout
import dev.omakey.core.icons.OmakeyIcons
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
        // Launched with the splash theme (icon on the ground colour); swap to the real one before
        // the first frame so the splash background doesn't sit behind the screen.
        setTheme(R.style.Theme_Omakey)
        super.onCreate(savedInstanceState)
        // Draws edge-to-edge (content behind the status/nav bars) so we control inset padding
        // ourselves via statusBarsPadding()/navigationBarsPadding() below — without this the
        // system draws an opaque status bar and the title row underneath it visually blends in.
        enableEdgeToEdge()
        // Before any preference is read: fresh installs get the new defaults, updates keep theirs.
        dev.omakey.core.settings.InstallDefaults.apply(applicationContext)
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
        if (dev.omakey.app.BuildConfig.SELF_UPDATE && updatePreferences.settings.value.autoCheckEnabled) {
            dev.omakey.app.update.UpdateWorkScheduler.schedule(applicationContext)
        }
        setContent {
            OmakeySettingsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = dev.omakey.app.design.Omakey.colors.ground) {
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

/** Settings' theme: the design tokens (dev.omakey.app.design), light or dark with the phone. */
@Composable
internal fun OmakeySettingsTheme(content: @Composable () -> Unit) = dev.omakey.app.design.OmakeyDesignTheme(content = content)

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
          // One screen at a time, moving the way it means: a page in from the right (where its
          // chevron points), back out to the right, search rising from below. See Motion.
          androidx.compose.animation.AnimatedContent(
            targetState = nav,
            contentKey = { it.screenKey() },
            transitionSpec = {
                val from = initialState.screenKey()
                val to = targetState.screenKey()
                when {
                    to.page != null && from.page == null -> dev.omakey.app.design.Motion.push()
                    to.page == null && from.page != null -> dev.omakey.app.design.Motion.pop()
                    to.search && !from.search -> dev.omakey.app.design.Motion.rise()
                    else -> dev.omakey.app.design.Motion.sink()
                }.apply { targetContentZIndex = if (to.page != null || to.search) 1f else 0f }
            },
            label = "settings-screen",
          ) { shown ->
            when (val page = shown.page) {
                null -> if (shown.searchOpen) {
                    SettingsSearchScreen(
                        query = shown.query,
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
                        summaries = mapOf(
                            SettingsPage.THEME to (if (useSystemAccent) "${currentTheme.name} · wallpaper colour" else currentTheme.name),
                            SettingsPage.FONT to (dev.omakey.app.keyboard.ui.FontCatalog.displayNames[currentFontId] ?: "System default"),
                        ),
                    )
                }

                else -> SettingsSubPage(
                    page = page,
                    onBack = back,
                    scrollTo = highlight?.section?.takeIf { it.page == page },
                    preview = if (page !in PREVIEW_PAGES) null else {
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
                        SettingsSectionKey.THEME -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ThemePicker(
                                themeRepository = themeRepository,
                                customThemePreferences = customThemePreferences,
                                layoutMode = layoutMode,
                                onCreateTheme = { themeBeingEdited = null; showThemeEditor = true },
                                onEditTheme = { theme -> themeBeingEdited = theme; showThemeEditor = true },
                            )
                            if (Build.VERSION.SDK_INT >= SettingKey.SYSTEM_ACCENT.minSdk) SettingsCard(contentPadding = 16.dp) {
                                SettingToggle(
                                    key = SettingKey.SYSTEM_ACCENT,
                                    description = "Colour the spacebar from your wallpaper.",
                                    checked = useSystemAccent,
                                    onCheckedChange = themeRepository::setUseSystemAccent,
                                )
                            }
                        }
                        SettingsSectionKey.KEYS -> {
                            AppearanceLayoutToggles(layoutSettings, layoutPreferences)
                            CapitalizationToggleSection(layoutSettings, layoutPreferences)
                            SecondarySymbolsToggle(layoutSettings, layoutPreferences)
                            TapPreviewStylePicker(layoutSettings, layoutPreferences)
                        }
                        SettingsSectionKey.FONT -> FontPicker(fontPreferences, currentFontId)
                        SettingsSectionKey.SIZE_POSITION -> {
                            ClickableSettingRow(
                                key = SettingKey.SIZE_POSITION,
                                description = "Taller, shorter, or lifted off the bottom.",
                                onClick = { showSizePositionOverlay = true },
                            )
                            EdgePaddingToggle(layoutSettings, layoutPreferences)
                            SplitDuplicateKeysToggle(layoutSettings, layoutPreferences)
                        }
                        SettingsSectionKey.SUGGESTION_BAR -> BarPagesPage(topStripPreferences)
                        SettingsSectionKey.QUICK_ACCESS -> QuickAccessTilesPage()
                        SettingsSectionKey.BUTTONS -> {
                            EmojiSlotKeyRow(layoutSettings, layoutPreferences)
                            SpacebarSideKeyRow(layoutSettings, layoutPreferences)
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
                                description = "Words omakey picked up from you.",
                                onClick = { showLearnedWordsOverlay = true },
                            )
                        }
                        SettingsSectionKey.CLIPBOARD -> {
                            ClipboardHistoryToggle(clipboardPreferences)
                            ClickableSettingRow(
                                key = SettingKey.CLIPBOARD_HISTORY,
                                description = "Pin, remove or clear what you copied.",
                                onClick = { showClipboardHistoryOverlay = true },
                            )
                        }
                        SettingsSectionKey.WHAT_LEAVES -> SettingBlock {
                            SettingDescription(stringResource(R.string.privacy_notice))
                        }
                        SettingsSectionKey.SOUND_HAPTICS -> FeedbackSettingsSection(hapticSoundPreferences, feedback)
                        SettingsSectionKey.ACCESSIBILITY -> AccessibleModeToggle(accessibilityPreferences)
                        SettingsSectionKey.UPDATES -> {
                            // Build number alongside the version name, because they can legitimately
                            // disagree: versionName is held fixed across dev installs within a release
                            // cycle while versionCode increments every time.
                            dev.omakey.app.design.SettingsRowLayout(
                                title = "Version ${dev.omakey.app.BuildConfig.VERSION_NAME}",
                                description = "Build ${dev.omakey.app.BuildConfig.VERSION_CODE}",
                                horizontalPadding = 0.dp,
                            )
                            // Play build: no update UI at all (see SELF_UPDATE in app/build.gradle.kts).
                            if (dev.omakey.app.BuildConfig.SELF_UPDATE) {
                            val autoCheckEnabled by updatePreferences.settings.collectAsState()
                            SettingToggle(
                                key = SettingKey.AUTO_UPDATE_CHECKS,
                                description = "Twice a day. Nothing installs by itself.",
                                checked = autoCheckEnabled.autoCheckEnabled,
                                onCheckedChange = onAutoUpdateCheckToggled,
                            )
                            UpdateCheckRow()
                            }
                        }
                    }
                }
            }
          }
        }

        // "Try it" on every page but search (where it would sit over the results and the
        // keyboard). Ink, bottom right, the one primary action that's always there.
        androidx.compose.animation.AnimatedVisibility(
            visible = !(nav.searchOpen && nav.page == null),
            enter = androidx.compose.animation.fadeIn(dev.omakey.app.design.Motion.out(200)) + androidx.compose.animation.scaleIn(dev.omakey.app.design.Motion.out(200), initialScale = 0.9f),
            exit = androidx.compose.animation.fadeOut(dev.omakey.app.design.Motion.out(150)) + androidx.compose.animation.scaleOut(dev.omakey.app.design.Motion.out(150), targetScale = 0.9f),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) {
            TryItButton(onClick = { showTestOverlay = true })
        }
    }

    dev.omakey.app.design.RisingPage(showTestOverlay) {
        TestKeyboardOverlay(onClose = { showTestOverlay = false }, onSwitchKeyboard = onSwitchKeyboard)
    }
    dev.omakey.app.design.PushedPage(showLearnedWordsOverlay) {
        LearnedWordsOverlay(
            wordDao = wordDao,
            profileOf = { id -> languagePacks.registry[id]?.profile ?: dev.omakey.core.locale.LanguageProfile.English },
            onClose = { showLearnedWordsOverlay = false },
        )
    }
    dev.omakey.app.design.PushedPage(showClipboardHistoryOverlay) {
        ClipboardHistoryOverlay(
            clipboardDao = clipboardDao,
            clipboardHistory = clipboardHistory,
            onClose = { showClipboardHistoryOverlay = false },
        )
    }
    dev.omakey.app.design.PushedPage(showSizePositionOverlay) {
        KeyboardSizePositionOverlay(
            layoutPreferences = layoutPreferences,
            theme = effectiveTheme,
            layoutMode = layoutMode,
            fontId = currentFontId,
            onClose = { showSizePositionOverlay = false },
        )
    }
    dev.omakey.app.design.PushedPage(showThemeEditor) {
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

/** Pages whose settings change how the keys look, so the live keyboard preview sits on top. */
private val PREVIEW_PAGES = setOf(SettingsPage.THEME, SettingsPage.KEYS, SettingsPage.FONT)

/** The icon tile for each page on the home list (design: Settings → Home). */
private fun SettingsPage.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    SettingsPage.THEME -> OmakeyIcons.Theme
    SettingsPage.KEYS -> OmakeyIcons.Keyboard
    SettingsPage.FONT -> OmakeyIcons.Type
    SettingsPage.SIZE_POSITION -> OmakeyIcons.Resize
    SettingsPage.CORRECTIONS -> OmakeyIcons.Book
    SettingsPage.GESTURES -> OmakeyIcons.Hand
    SettingsPage.LANGUAGES -> OmakeyIcons.Globe
    SettingsPage.BAR_PAGES -> OmakeyIcons.QuickAccess
    SettingsPage.QUICK_ACCESS -> OmakeyIcons.QuickAccess
    SettingsPage.CLIPBOARD -> OmakeyIcons.Clipboard
    SettingsPage.SOUND -> OmakeyIcons.Sound
    SettingsPage.PRIVACY -> OmakeyIcons.Shield
    SettingsPage.ACCESSIBILITY -> OmakeyIcons.Accessibility
    SettingsPage.ABOUT -> OmakeyIcons.Info
}

@Composable
private fun SettingsHome(
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (SettingsPage) -> Unit,
    onSearch: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    summaries: Map<SettingsPage, String>,
) {
    val c = Omakey.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = Omakey.type.headline,
                    color = c.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.tonal)
                        .clickable(onClickLabel = "Search settings", onClick = onSearch)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(OmakeyIcons.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                    Text("Search settings", style = Omakey.type.row.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.muted)
                }
            }
        }
        item { SetupStatusCard(onOpenSystemSettings, onSwitchKeyboard) }
        SettingsGroup.entries.forEach { group ->
            item(key = group) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(group.title)
                    SettingsCard(dividerInset = 70.dp) {
                        SettingsIndex.pagesIn(group).forEach { page ->
                            HomeRow(page.title, summaries[page] ?: page.summary, page.icon()) { onOpen(page) }
                            // Snippets opens its own screen, so it isn't a page; it sits after Bar pages.
                            if (page == SettingsPage.BAR_PAGES) {
                                HomeRow("Snippets", "Saved text and passwords", OmakeyIcons.Snippet) {
                                    openSnippets(context)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Search finds any setting by name.",
                style = Omakey.type.caption,
                color = c.subtle,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HomeRow(title: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    SettingsRowLayout(
        title = title,
        description = summary,
        icon = icon,
        trailing = RowTrailing.Chevron,
        modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
    )
}

/**
 * One card that says where setup stands and offers the single next step: turn omakey on, make it
 * the keyboard, or (once both are done) try it. Re-checked on every resume, since the steps happen
 * in the system's own screens.
 */
@Composable
private fun SetupStatusCard(onOpenSystemSettings: () -> Unit, onSwitchKeyboard: () -> Unit) {
    val c = Omakey.colors
    val (isEnabled, isDefault) = rememberSetupStatus()
    val ready = isEnabled && isDefault
    val title = when {
        !isEnabled -> "Turn on omakey"
        !isDefault -> "Make omakey your keyboard"
        else -> "omakey is ready"
    }
    val body = when {
        !isEnabled -> "Allow it in your phone's keyboard list."
        !isDefault -> "Pick it in the keyboard switcher."
        else -> "Your keyboard in every app"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dev.omakey.app.design.Radius.card))
            .background(c.surface)
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(if (ready) c.ink else c.tonal, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ready) OmakeyIcons.Check else OmakeyIcons.Keyboard,
                contentDescription = null,
                tint = if (ready) c.onInk else c.ink,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Omakey.type.row, color = c.ink)
            Text(body, style = Omakey.type.body, color = c.muted)
        }
        when {
            !isEnabled -> OmakeyButton("Turn on", onOpenSystemSettings, kind = ButtonKind.Primary, height = 40.dp)
            !isDefault -> OmakeyButton("Choose", onSwitchKeyboard, kind = ButtonKind.Primary, height = 40.dp)
            // Ready: nothing to do here; "Try it" is the button at the bottom of every page.
            else -> Unit
        }
    }
}

/** How long a setting opened from search stays highlighted. */
private const val HIGHLIGHT_MILLIS = 1500L

private val SettingsNavSaver = androidx.compose.runtime.saveable.listSaver<SettingsNav, Any?>(
    save = { listOf(it.page?.name, it.searchOpen, it.query) },
    // A page renamed since the state was saved (the 2026-11 restructure) restores to home.
    restore = { SettingsNav((it[0] as String?)?.let { name -> runCatching { SettingsPage.valueOf(name) }.getOrNull() }, it[1] as Boolean, it[2] as String) },
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
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { PageHeader(title = page.title, onBack = onBack, modifier = Modifier.padding(start = 4.dp)) }
        if (preview != null) item { preview() }
        sections.forEach { key ->
            item(key = key) {
                when {
                    // These two have no label, so the search flash goes on the content itself.
                    key.bare -> Box(sectionHighlight(key)) { section(key) }
                    // A page with one section named like the page: no label repeating the title.
                    sections.size == 1 && key.title == page.title ->
                        SettingsCard(sectionHighlight(key), contentPadding = 16.dp, dividerInset = 0.dp) { section(key) }
                    else -> SettingsSection(key) { section(key) }
                }
            }
        }
    }
}

/** The search flash for a section whose picker has no row title of its own (Theme, Font…). */
@Composable
private fun sectionHighlight(key: SettingsSectionKey): Modifier {
    val entry = SettingKey.entries.firstOrNull { it.sectionLevel && it.section == key }
    return if (entry != null) rememberSettingHighlight(entry) else Modifier
}

/** Which screen a nav state shows, for the transition: the query changing isn't a new screen. */
private data class ScreenKey(val page: SettingsPage?, val search: Boolean)

private fun SettingsNav.screenKey() = ScreenKey(page, searchOpen && page == null)

/** The ink "Try it" button: a keyboard icon and the verb, 56 tall. */
@Composable
private fun TryItButton(onClick: () -> Unit) {
    val c = Omakey.colors
    Row(
        Modifier
            .height(56.dp)
            .shadow(6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(c.ink)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Try the keyboard", onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(OmakeyIcons.Keyboard, contentDescription = null, tint = c.onInk, modifier = Modifier.size(22.dp))
        Text("Try it", style = Omakey.type.button, color = c.onInk)
    }
}

/** Opens the Snippets screen pushed in from the right, matching the in-app pages. */
internal fun openSnippets(context: android.content.Context) {
    val options = android.app.ActivityOptions.makeCustomAnimation(context, R.anim.page_in_right, R.anim.page_out_left)
    context.startActivity(Intent(context, SnippetsActivity::class.java), options.toBundle())
}
