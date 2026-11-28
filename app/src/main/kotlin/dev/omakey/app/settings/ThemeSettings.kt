package dev.omakey.app.settings

import dev.omakey.core.layout.withSymbolsPage
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.omakey.app.design.ActionSheet
import dev.omakey.app.design.ButtonKind
import dev.omakey.app.design.SheetAction
import dev.omakey.app.design.ConfirmSheet
import dev.omakey.app.design.IconAction
import dev.omakey.app.design.Omakey
import dev.omakey.app.design.OmakeyButton
import dev.omakey.app.design.OmakeyField
import dev.omakey.app.design.PageHeader
import dev.omakey.app.design.Radius
import dev.omakey.app.design.SectionLabel
import dev.omakey.app.design.Segmented
import dev.omakey.app.design.SettingsCard
import dev.omakey.core.icons.OmakeyIcons
import androidx.compose.ui.text.font.FontWeight
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.icons.PhosphorLineCopy
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.app.keyboard.ui.gridBorderExceptBottom
import dev.omakey.core.theme.toDp
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.ColorSpec
import dev.omakey.core.theme.CustomThemePreferences
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.Presets
import dev.omakey.core.theme.ThemeRepository
import dev.omakey.core.theme.ThemeDerivation
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/*
 * Theme selection and the custom-theme editor: the picker, the swatch, the editor carousel, the HSV
 * colour picker and the colour maths behind them. Split out of SettingsActivity.kt — this is a
 * self-contained feature that happened to live in the same file as everything else.
 */

/** Normal vs. Grid — orthogonal to the color theme picked via [ThemePicker] below (every color
 * theme works with either layout mode, see [dev.omakey.core.theme.LayoutMode]'s doc), shown
 * first in Appearance since it decides how the rest of the section's options apply (e.g. "Key
 * backgrounds" is a Normal-mode-only concept). Material3's own segmented-button pill row — the
 * platform's standard two/three-way switch control — rather than a bespoke bordered-box list, and
 * with no checkmark icon (`icon = {}` suppresses `SegmentedButton`'s default one): the pill's own
 * filled/outlined state already communicates which option is selected. */
@Composable
internal fun LayoutModePicker(themeRepository: ThemeRepository) {
    val currentMode by themeRepository.layoutMode.collectAsState()
    val options = listOf(
        dev.omakey.core.theme.LayoutMode.NORMAL to "Normal",
        dev.omakey.core.theme.LayoutMode.GRID to "Grid",
    )
    SettingBlock {
        Segmented(options, currentMode, themeRepository::setLayoutMode)
        SettingDescription(
            if (currentMode == dev.omakey.core.theme.LayoutMode.GRID) "Keys sit in a grid of thin lines." else "Keys float on the keyboard, like Fleksy.",
        )
    }
}

/** Split into two sub-sections, per real user feedback: a custom theme built while previewing one
 * layout mode often has fields (`gridBorderColor`/`gridBorderWidth`) that were never actually
 * looked at for the *other* mode, so it "doesn't always work" there — not broken, just half-tuned.
 * The 4 built-in presets aren't affected by that (they're tuned for both modes already), so they
 * stay a flat always-visible picker; only the custom-theme list below them filters by
 * [layoutMode]. */
@Composable
internal fun ThemePicker(
    themeRepository: ThemeRepository,
    customThemePreferences: CustomThemePreferences,
    layoutMode: dev.omakey.core.theme.LayoutMode,
    onCreateTheme: () -> Unit,
    onEditTheme: (OmakeyTheme) -> Unit,
) {
    val currentTheme by themeRepository.currentTheme.collectAsState()
    val customThemes by customThemePreferences.themes.collectAsState()
    var themeToDelete by remember { mutableStateOf<OmakeyTheme?>(null) }
    var themeWithActions by remember { mutableStateOf<OmakeyTheme?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val presetLabels = mapOf(
            Presets.Light.id to "Light",
            Presets.Dark.id to "Dark",
            Presets.Auto.id to "Auto",
            Presets.Accent.id to "Wallpaper",
        )
        // Accent (wallpaper colours) only where the phone has them: see themePresetChoices.
        val presetOptions = dev.omakey.app.keyboard.themePresetChoices(android.os.Build.VERSION.SDK_INT, currentTheme.id)
            .map { it.id to presetLabels.getValue(it.id) }
        val presets = listOf(Presets.Light, Presets.Dark, Presets.Auto, Presets.Accent).associateBy { it.id }
        // A custom theme is on: no preset is chosen, and the segmented control shows none lit.
        Segmented(presetOptions, currentTheme.id, onSelect = { id -> presets[id]?.let(themeRepository::setTheme) })
        SettingDescription(
            if (presetOptions.any { it.first == Presets.Accent.id }) {
                "Auto follows your phone's light and dark mode. Wallpaper takes its colours from your wallpaper."
            } else {
                "Auto follows your phone's light and dark mode."
            },
            Modifier.padding(horizontal = 4.dp),
        )

        // null designedForLayoutMode = made before this field existed: shown in both modes.
        val visibleCustomThemes = customThemes.filter { it.designedForLayoutMode == null || it.designedForLayoutMode == layoutMode }
        SectionLabel("Your themes", Modifier.padding(top = 12.dp))
        if (visibleCustomThemes.isEmpty() && customThemes.isNotEmpty()) {
            SettingDescription(
                "Your themes were made for ${if (layoutMode == dev.omakey.core.theme.LayoutMode.GRID) "Normal" else "Grid"} keys. Switch layout style to see them.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            visibleCustomThemes.forEach { theme ->
                ThemeTile(
                    theme = theme,
                    selected = theme.id == currentTheme.id,
                    onClick = { themeRepository.setTheme(theme) },
                    onLongClick = { themeWithActions = theme },
                )
            }
            NewThemeTile(onCreateTheme)
        }
        if (visibleCustomThemes.isNotEmpty()) {
            Text("Hold a theme to edit or delete it.", style = Omakey.type.caption, color = Omakey.colors.subtle, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }

    themeWithActions?.let { theme ->
        ActionSheet(
            title = theme.name,
            actions = listOf(
                SheetAction("Edit theme", OmakeyIcons.Theme) {
                    themeWithActions = null
                    onEditTheme(theme)
                },
                SheetAction("Delete theme", OmakeyIcons.Trash, destructive = true) {
                    themeWithActions = null
                    themeToDelete = theme
                },
            ),
            onDismiss = { themeWithActions = null },
        )
    }

    themeToDelete?.let { theme ->
        ConfirmSheet(
            title = "Delete \"${theme.name}\"?",
            message = if (currentTheme.id == theme.id) "It's on now, so the keyboard goes back to Dark." else "You can't get it back.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                customThemePreferences.delete(theme.id)
                if (currentTheme.id == theme.id) themeRepository.setTheme(Presets.Dark)
                themeToDelete = null
            },
            onDismiss = { themeToDelete = null },
        )
    }
}

/** A custom theme as a tile: a miniature of its keys, its name under it; an ink ring when on. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ThemeTile(theme: OmakeyTheme, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Omakey.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(Radius.control))
                .border(2.dp, if (selected) c.ink else Color.Transparent, RoundedCornerShape(Radius.control))
                .combinedClickable(onClickLabel = "Use ${theme.name}", onLongClickLabel = "Edit or delete", onClick = onClick, onLongClick = onLongClick)
                .semantics { this.selected = selected },
        ) { KeyboardSwatch(theme) }
        Text(
            theme.name,
            style = if (selected) Omakey.type.label else Omakey.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
            color = if (selected) c.ink else c.muted,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.width(80.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun NewThemeTile(onClick: () -> Unit) {
    val c = Omakey.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(Radius.control))
                .background(c.tonal)
                .clickable(onClickLabel = "Make a new theme", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(OmakeyIcons.Plus, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp)) }
        Text("New", style = Omakey.type.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.muted)
    }
}

/** A theme's palette as a miniature keyboard: three rows of keys, the last key in its accent. */
@Composable
private fun KeyboardSwatch(theme: OmakeyTheme) {
    val key = theme.keyBackground.toComposeColor()
    Column(
        Modifier.fillMaxSize().background(theme.keyboardBackground.toComposeColor()).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(4) { col ->
                    val isAccent = row == 2 && col == 3
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(if (isAccent) theme.accent.toComposeColor() else key, RoundedCornerShape(3.dp)),
                    )
                }
            }
        }
    }
}


/** "Build your own theme" — full-screen editor for exactly the 4 colors requested (background,
 * key, home-row stripe, spacebar), matching [LearnedWordsOverlay]'s full-screen-overlay pattern.
 * Every other [OmakeyTheme] field (text color, pressed/special key backgrounds, suggestion bar,
 * `isDark`) is derived automatically from those 4 by WCAG contrast rules in
 * [buildCustomTheme] — a 9-field form would defeat the point of scoping this to "the 4 colors a
 * user actually thinks about." [initialTheme] non-null means editing an existing custom theme
 * (keeps its id); null means creating a new one. */
@Composable
internal fun ThemeEditorOverlay(
    initialTheme: OmakeyTheme?,
    layoutMode: dev.omakey.core.theme.LayoutMode,
    layoutPreferences: LayoutPreferences,
    onSave: (OmakeyTheme) -> Unit,
    onClose: () -> Unit,
) {
    // "Key color" (below) only has anything to actually paint in Normal mode if key backgrounds
    // are turned on at all — LayoutSettings.showKeyBackgrounds, the same "Key backgrounds"
    // Appearance toggle, is what gates Color.Transparent vs. theme.keyBackground in KeyRowView.
    // Surfaced right next to the colour it controls, and the preview reflects it.
    val layoutSettings by layoutPreferences.settings.collectAsState()
    // Name is entered at save time (the "Save theme" dialog below), not as an always-visible field.
    var name by remember { mutableStateOf(initialTheme?.name ?: "My theme") }
    var showSaveNamePrompt by remember { mutableStateOf(false) }
    var showDiscardPrompt by remember { mutableStateOf(false) }
    val themeId = remember { initialTheme?.id ?: (CustomThemePreferences.ID_PREFIX + java.util.UUID.randomUUID().toString()) }

    // Everything follows the background (AGENTS.md §71): picking six related colours by hand is
    // hard to get right, so each one starts derived — readable, light or dark to match — and the
    // user overrides only what they want. Null means "automatic"; an automatic colour keeps
    // following the background as it changes. A new theme starts from the system's light or dark.
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val initialBackground = initialTheme?.keyboardBackground ?: ColorSpec(if (systemDark) 0xFF1E1E1E else 0xFFF2F2F2)
    var background by remember { mutableStateOf(initialBackground.toComposeColor()) }
    // An existing theme's colour counts as automatic if it is exactly what derivation would give
    // for its background, so re-opening it keeps the auto behaviour instead of freezing everything.
    val initialDerived = remember { ThemeDerivation.fromBackground(initialBackground, initialTheme?.keyBackground) }
    fun stored(value: ColorSpec?, derived: ColorSpec): Color? = value?.takeIf { it != derived }?.toComposeColor()
    var keyColor by remember {
        mutableStateOf(initialTheme?.keyBackground?.takeIf { it != ThemeDerivation.fromBackground(initialBackground).key }?.toComposeColor())
    }
    var stripeColor by remember { mutableStateOf(stored(initialTheme?.middleRowStripeColor, initialDerived.homeRowStripe)) }
    var spacebarColor by remember { mutableStateOf(stored(initialTheme?.spacebarAccentColor, initialDerived.spacebar)) }
    var keyTapColor by remember { mutableStateOf(stored(initialTheme?.keyBackgroundPressed, initialDerived.keyTap)) }
    var accentColor by remember { mutableStateOf(stored(initialTheme?.accentColor, initialDerived.accent)) }
    var gridBorderColor by remember { mutableStateOf(stored(initialTheme?.gridBorderColor, initialDerived.gridBorder)) }
    var gridBorderWidth by remember {
        mutableStateOf(initialTheme?.gridBorderWidth ?: dev.omakey.core.theme.GridBorderWidth.MD)
    }

    val derived = remember(background, keyColor) {
        ThemeDerivation.fromBackground(background.toColorSpec(), keyColor?.toColorSpec())
    }

    val previewTheme = remember(name, background, derived, keyTapColor, stripeColor, spacebarColor, accentColor, gridBorderColor, gridBorderWidth) {
        buildCustomTheme(
            id = themeId,
            name = name.ifBlank { "My theme" },
            backgroundColor = background,
            keyColor = derived.key.toComposeColor(),
            keyTapColor = keyTapColor ?: derived.keyTap.toComposeColor(),
            stripeColor = stripeColor ?: derived.homeRowStripe.toComposeColor(),
            spacebarColor = spacebarColor ?: derived.spacebar.toComposeColor(),
            accentColor = accentColor ?: derived.accent.toComposeColor(),
            gridBorderColor = gridBorderColor ?: derived.gridBorder.toComposeColor(),
            gridBorderWidth = gridBorderWidth,
            // Tagged with whichever mode is being edited right now: the fields being edited are
            // for this mode.
            designedForLayoutMode = layoutMode,
        )
    }
    // What "no changes" looks like, for the discard prompt. Captured once, so it is the theme as
    // it was opened rather than as it is now.
    val openedAs = remember { previewTheme }
    val dirty = previewTheme != openedAs
    val close = { if (dirty) showDiscardPrompt = true else onClose() }
    BackHandler(onBack = close)

    val isGrid = layoutMode == dev.omakey.core.theme.LayoutMode.GRID
    // Order follows how a theme is usually built: the surface first, then its structure (keys or
    // the grid's lines), then the details. "Key color" is Normal-only — every Grid cell fills with
    // Background — and "Grid border" with its thickness is Grid-only.
    val editPages = buildList {
        add(ThemeEditField("Background", background, isAuto = null) { background = it })
        if (!isGrid) {
            add(ThemeEditField("Key color", derived.key.toComposeColor(), isAuto = keyColor == null, onReset = { keyColor = null }) { keyColor = it })
        }
        if (isGrid) {
            add(
                ThemeEditField(
                    "Grid border", gridBorderColor ?: derived.gridBorder.toComposeColor(),
                    isAuto = gridBorderColor == null, onReset = { gridBorderColor = null },
                    extra = { GridBorderWidthPicker(gridBorderWidth) { gridBorderWidth = it } },
                ) { gridBorderColor = it },
            )
        }
        add(ThemeEditField("Home-row stripe", stripeColor ?: derived.homeRowStripe.toComposeColor(), isAuto = stripeColor == null, onReset = { stripeColor = null }) { stripeColor = it })
        add(ThemeEditField("Spacebar", spacebarColor ?: derived.spacebar.toComposeColor(), isAuto = spacebarColor == null, onReset = { spacebarColor = null }) { spacebarColor = it })
        add(ThemeEditField("Key tap color", keyTapColor ?: derived.keyTap.toComposeColor(), isAuto = keyTapColor == null, onReset = { keyTapColor = null }) { keyTapColor = it })
        add(ThemeEditField("Accent (caps lock, active buttons)", accentColor ?: derived.accent.toComposeColor(), isAuto = accentColor == null, onReset = { accentColor = null }) { accentColor = it })
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Omakey.colors.ground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageHeader(title = if (initialTheme == null) "New theme" else "Edit theme", onBack = close, modifier = Modifier.padding(start = 4.dp))

            // The preview stays put while the colour pages below are swiped, so every change shows.
            androidx.compose.runtime.CompositionLocalProvider(
                dev.omakey.core.theme.LocalKeyboardLayoutMode provides layoutMode,
            ) {
                ThemePreviewMock(previewTheme, showKeyBackgrounds = layoutSettings.showKeyBackgrounds, interactive = true, tapPreviewStyle = layoutSettings.tapPreviewStyle)
            }

            // "Key colour" only shows in Normal mode with key backgrounds on, so the switch sits here,
            // next to the colour it gates.
            if (layoutMode != dev.omakey.core.theme.LayoutMode.GRID) {
                SettingsCard(contentPadding = 16.dp) {
                    UnindexedSettingToggle(
                        title = "Key backgrounds",
                        description = null,
                        checked = layoutSettings.showKeyBackgrounds,
                        onCheckedChange = layoutPreferences::setShowKeyBackgrounds,
                    )
                }
            }

            ThemeEditCarousel(
                pages = editPages,
                modifier = Modifier.weight(1f),
            )

            OmakeyButton("Save theme", { showSaveNamePrompt = true }, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showDiscardPrompt) {
        ConfirmSheet(
            title = "Leave without saving?",
            message = "Your changes to this theme will be lost.",
            confirmLabel = "Leave",
            destructive = true,
            dismissLabel = "Keep editing",
            onConfirm = onClose,
            onDismiss = { showDiscardPrompt = false },
        )
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

/** Border thickness, shown on the "Grid border" page — the only place it means anything. */
@Composable
private fun GridBorderWidthPicker(
    selected: dev.omakey.core.theme.GridBorderWidth,
    onSelect: (dev.omakey.core.theme.GridBorderWidth) -> Unit,
) {
    val widthOptions = listOf(
        dev.omakey.core.theme.GridBorderWidth.SM to "Thin",
        dev.omakey.core.theme.GridBorderWidth.MD to "Medium",
        dev.omakey.core.theme.GridBorderWidth.LG to "Thick",
    )
    Segmented(widthOptions, selected, onSelect, Modifier.width(280.dp))
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
    ConfirmSheet(
        title = "Name this theme",
        message = null,
        confirmLabel = "Save theme",
        dismissLabel = "Keep editing",
        onConfirm = { onConfirm(nameInput) },
        onDismiss = onDismiss,
    ) {
        OmakeyField(value = nameInput, onValueChange = { nameInput = it }, modifier = Modifier.padding(top = 8.dp))
    }
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
internal fun ThemePreviewMock(
    theme: OmakeyTheme,
    showKeyBackgrounds: Boolean = false,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    homeRowTinted: Boolean = true,
    alwaysShowUppercaseLetters: Boolean = true,
    edgePadding: Boolean = false,
    showSecondarySymbols: Boolean = false,
    /** Whether the letters' long-press symbols follow the Fleksy symbols page — see
     * `LayoutSettings.fleksySymbols`; matters only when [showSecondarySymbols] draws them. */
    fleksySymbols: Boolean = true,
    // The keys behave like the real keyboard's, so a setting can be felt before it is used: a held
    // key lights up (or rises, with Lift), its pop-up shows in the chosen style, Shift steps
    // through off → shift → caps lock and a letter drops a one-shot shift, a swipe left plays the
    // delete sweep and swoosh, and every touch vibrates and clicks as set. Nothing is typed.
    interactive: Boolean = false,
    tapPreviewStyle: dev.omakey.core.layout.TapPreviewStyle = dev.omakey.core.layout.TapPreviewStyle.OFF,
    /** The keyboard's own vibration and sound; null for none. */
    feedback: dev.omakey.app.keyboard.KeyboardFeedback? = null,
    /** The user's swipe sensitivity, so a swipe here needs the distance it needs on the keyboard. */
    swipeSensitivity: Float = 1f,
) {
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }
    var rowsCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val ancestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { rowsCoordinates } }
    val keyBounds = remember { HashMap<Int, Pair<dev.omakey.core.layout.KeyDefinition, androidx.compose.ui.geometry.Rect>>() }
    var pressedCode by remember { mutableStateOf<Int?>(null) }
    val tapPreview = remember { dev.omakey.app.keyboard.ui.TapPreviewState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Read inside the long-lived gesture loop, so a setting changed below applies to the next touch.
    val currentStyle by androidx.compose.runtime.rememberUpdatedState(tapPreviewStyle)
    val currentFeedback by androidx.compose.runtime.rememberUpdatedState(feedback)
    val currentSensitivity by androidx.compose.runtime.rememberUpdatedState(swipeSensitivity)
    // The swipe-left delete sweep, timed as in KeyGrid.
    var shimmerTrigger by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val shimmerProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(shimmerTrigger) {
        if (shimmerTrigger == 0) return@LaunchedEffect
        shimmerProgress.snapTo(1f)
        shimmerProgress.animateTo(0f, androidx.compose.animation.core.tween(225, easing = androidx.compose.animation.core.LinearEasing))
    }
    var shiftOn by remember { mutableStateOf(false) }
    var capsLockOn by remember { mutableStateOf(false) }
    val rowHeightDp = 44
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.keyboardBackground.toComposeColor(), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp)
            // Mirrors KeyboardRoot's own gutters, inside the background for the same reason — this
            // preview's whole job is to be what the keyboard will actually look like.
            .padding(horizontal = if (edgePadding) LayoutSettings.EDGE_PADDING_DP.dp else 0.dp),
    ) {
        val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(if (isGridMode) theme.keyboardBackground.toComposeColor() else theme.suggestionBarBackground.toComposeColor())
                // Grid mode draws two *container* outlines that its per-cell right+bottom borders
                // can't: this one around the strip, and the one around the key rows below. Without
                // them the preview showed cell dividers but no top/left/right edge, and no seam at
                // all under the empty part of the strip — reported as "missing outline in some
                // parts". Deliberately the same two helpers TopStrip and KeyGrid use, rather than
                // a lookalike, so the preview cannot drift from the real thing again.
                .let { m ->
                    if (isGridMode) {
                        m.gridBorderExceptBottom(theme.gridBorderColor.toComposeColor(), theme.gridBorderWidth.toDp())
                    } else {
                        m
                    }
                },
        ) {
            dev.omakey.app.keyboard.ui.SuggestionsTabContent(
                suggestions = listOf("hello", "world"),
                emojiSuggestions = listOf("😊"),
                firstSuggestionKind = dev.omakey.app.keyboard.SuggestionKind.PLAIN,
                activeSuggestionIndex = -1,
                // The Settings preview shows a fixed sample, never a live engine.
                loading = false,
                theme = theme,
                fontFamily = fontFamily,
                showKeyBackgrounds = showKeyBackgrounds,
                onAccept = {},
                onAcceptEmoji = {},
            )
        }
        // Matches KeyGrid's own container border — see the strip's comment above. Its cells already
        // draw right+bottom, so this contributes the top and left edges.
        Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .let { m ->
                    if (isGridMode) {
                        m.border(theme.gridBorderWidth.toDp(), theme.gridBorderColor.toComposeColor())
                    } else {
                        m
                    }
                }
                .let { m ->
                    if (!interactive) return@let m
                    m.onGloballyPositioned { rowsCoordinates = it }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val hit = keyBounds.values.firstOrNull { it.second.contains(down.position) }
                                val code = hit?.first?.code
                                val isCharacter = hit?.first?.keyType == dev.omakey.core.layout.KeyType.CHARACTER
                                pressedCode = code
                                currentFeedback?.onKeyPress()
                                if (isCharacter && currentStyle.showsBubble) tapPreview.hold(hit!!)
                                // The same distance a swipe needs on the keyboard (KeyGrid).
                                val swipeDistance = size.width * 0.13f * currentSensitivity
                                var swiped = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    // Taken here, so a drag across the keys swipes instead of
                                    // scrolling the page.
                                    change.consume()
                                    if (!change.pressed) break
                                    val dx = change.position.x - down.position.x
                                    if (!swiped && kotlin.math.abs(dx) > swipeDistance) {
                                        swiped = true
                                        pressedCode = null
                                        tapPreview.hide()
                                        if (dx < 0) {
                                            currentFeedback?.onSwipeDelete()
                                            shimmerTrigger++
                                        } else {
                                            currentFeedback?.onSwipe()
                                        }
                                    }
                                }
                                pressedCode = null
                                if (!swiped) {
                                    tapPreview.release(scope)
                                    when {
                                        code == dev.omakey.core.layout.SpecialKeyCode.SHIFT -> when {
                                            capsLockOn -> { capsLockOn = false; shiftOn = false }
                                            shiftOn -> capsLockOn = true
                                            else -> shiftOn = true
                                        }
                                        // A letter uses up a one-shot shift, as when typing.
                                        isCharacter && shiftOn && !capsLockOn -> shiftOn = false
                                    }
                                }
                            }
                        }
                },
        ) {
        val previewLayout = remember(fleksySymbols) {
            KeyboardLocale.Default.letterLayout.let {
                if (fleksySymbols) {
                    it.withSymbolsPage(dev.omakey.core.layout.Layouts.ClassicSymbols1, dev.omakey.core.layout.Layouts.Symbols1)
                } else {
                    it
                }
            }
        }
        previewLayout.rows.forEachIndexed { rowIndex, row ->
            dev.omakey.app.keyboard.ui.KeyRowView(
                rowKeys = row.keys,
                rowHeightDp = rowHeightDp,
                shiftOn = shiftOn,
                theme = theme,
                accessibleMode = false,
                showKeyBackgrounds = showKeyBackgrounds,
                pressedKeyCode = { pressedCode },
                capsLockOn = capsLockOn,
                // Matches KeyGrid's own homeRowIndex for QwertyEnUS (see KeyboardRoot.kt) — the
                // ASDFGHJKL row (index 1), not the ZXCVBNM/shift row (real bug, fixed: this
                // preview had it one row too low).
                isHomeRow = rowIndex == KeyboardLocale.Default.letterLayout.homeRow,
                homeRowTinted = homeRowTinted,
                onKeyTap = {},
                ancestorCoordinates = if (interactive) ancestor else noOpAncestor,
                // Keyed by row and position, as KeyGrid does (rowIndex * 1000 + index). The first
                // value is the key's index in its row, not its code: keyed by it alone, every row
                // overwrote the same slots and only the last row measured could be tapped.
                onBoundsMeasured = { measured -> measured.forEach { (index, key, rect) -> keyBounds[rowIndex * 1000 + index] = key to rect } },
                fontFamily = fontFamily,
                alwaysShowUppercaseLetters = alwaysShowUppercaseLetters,
                showSecondarySymbols = showSecondarySymbols,
                shimmerProgress = shimmerProgress.value,
                liftPressedKey = tapPreviewStyle == dev.omakey.core.layout.TapPreviewStyle.LIFT,
            )
        }
        }
        if (interactive && tapPreviewStyle.showsBubble) {
            dev.omakey.app.keyboard.ui.TapPreviewBubble(
                tapPreview, theme, fontFamily, ancestor,
                slim = tapPreviewStyle == dev.omakey.core.layout.TapPreviewStyle.SLIM,
            )
        }
        }
    }
}

/** One page of [ThemeEditCarousel] — a single color field being edited. */
/** One carousel page. [isAuto] null means the field has no automatic value (the background);
 * otherwise false shows a "Reset to automatic" button calling [onReset]. [extra] sits under the
 * picker (the grid border's thickness). */
private class ThemeEditField(
    val label: String,
    val color: Color,
    val isAuto: Boolean? = null,
    val onReset: () -> Unit = {},
    val extra: (@Composable () -> Unit)? = null,
    val onColorChange: (Color) -> Unit,
)

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
                            .size(28.dp)
                            .background(field.color, CircleShape)
                            .border(1.dp, Omakey.colors.hairline, CircleShape),
                    )
                    Text(text = field.label, style = Omakey.type.row, color = Omakey.colors.ink)
                    when (field.isAuto) {
                        true -> Text(text = "Automatic", style = Omakey.type.caption, color = Omakey.colors.muted)
                        false -> OmakeyButton("Make automatic", field.onReset, height = 36.dp)
                        null -> Unit
                    }
                }
                HsvColorPicker(color = field.color, onColorChange = field.onColorChange)
                field.extra?.invoke()
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
                        .size(width = if (index == pagerState.currentPage) 16.dp else 6.dp, height = 6.dp)
                        .background(
                            if (index == pagerState.currentPage) Omakey.colors.ink else Omakey.colors.hairline,
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

    // An automatic colour changes from outside as the background does (or on "Reset to
    // automatic"); follow it. A change this picker made itself already matches, so dragging is
    // never fought.
    androidx.compose.runtime.LaunchedEffect(color) {
        val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
        if (current.toArgb() != color.toArgb()) {
            val out = FloatArray(3)
            android.graphics.Color.colorToHSV(color.toArgb(), out)
            hue = out[0]; saturation = out[1]; value = out[2]
            hexText = colorToHexString(color)
        }
    }

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
                    .clip(RoundedCornerShape(Radius.control))
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
                        // Lambda overload: saturation/value change on every pointer move while
                        // dragging, and this form re-runs layout instead of recomposing the Box.
                        .offset {
                            IntOffset(
                                x = ((maxWidth * saturation - 6.dp).toPx()).roundToInt(),
                                y = ((maxHeight * (1f - value) - 6.dp).toPx()).roundToInt(),
                            )
                        }
                        .size(14.dp)
                        .border(2.5.dp, Color.White, CircleShape),
                )
            }
            BoxWithConstraints(
                modifier = Modifier
                    .width(28.dp)
                    .height(160.dp)
                    .clip(CircleShape)
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
            ) {
                // The thumb: where the chosen hue sits on the track.
                Box(
                    Modifier
                        .offset { IntOffset(0, ((maxHeight * (hue / 360f) - 7.dp).toPx()).roundToInt().coerceIn(0, (maxHeight - 14.dp).toPx().roundToInt())) }
                        .align(Alignment.TopCenter)
                        .size(width = 28.dp, height = 14.dp)
                        .border(2.5.dp, Color.White, CircleShape),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OmakeyField(
                value = hexText,
                onValueChange = { applyHex(it) },
                modifier = Modifier.weight(1f),
                textStyle = Omakey.type.row.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
            )
            IconAction(OmakeyIcons.Copy, "Copy colour code", { clipboardManager.setText(AnnotatedString(hexText)) })
        }
    }
}

private fun colorToHexString(color: Color): String = "#%06X".format(0xFFFFFF and color.toArgb())

/** The editor's colours as a theme. Everything it does not expose (label text, special keys,
 * the suggestion bar, light/dark) is worked out by [ThemeDerivation.buildTheme] in `core`, where
 * `WcagContrastTest` checks it across a sweep of colours. This used to derive those here with raw
 * RGB nudges, judged light/dark by the key rather than the background, and was untested. */
internal fun buildCustomTheme(
    id: String,
    name: String,
    backgroundColor: Color,
    keyColor: Color,
    keyTapColor: Color,
    stripeColor: Color,
    spacebarColor: Color,
    accentColor: Color,
    gridBorderColor: Color,
    gridBorderWidth: dev.omakey.core.theme.GridBorderWidth,
    designedForLayoutMode: dev.omakey.core.theme.LayoutMode,
): OmakeyTheme {
    val background = backgroundColor.toColorSpec()
    val palette = ThemeDerivation.Palette(
        isDark = ThemeDerivation.isDark(background),
        key = keyColor.toColorSpec(),
        keyTap = keyTapColor.toColorSpec(),
        homeRowStripe = stripeColor.toColorSpec(),
        spacebar = spacebarColor.toColorSpec(),
        accent = accentColor.toColorSpec(),
        gridBorder = gridBorderColor.toColorSpec(),
    )
    return ThemeDerivation.buildTheme(id, name, background, palette, gridBorderWidth, designedForLayoutMode)
}

private fun Color.toColorSpec(): ColorSpec = ColorSpec.fromArgbInt(this.toArgb())
