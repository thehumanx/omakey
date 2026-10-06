package dev.omakey.app.settings

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
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.icons.PhosphorCopy
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
                        .background(theme.accent.toComposeColor(), CircleShape),
                )
            }
        }
    }
}


/** "Build your own theme" — full-screen editor for exactly the 4 colors requested (background,
 * key, home-row stripe, spacebar), matching [LearnedWordsOverlay]'s full-screen-overlay pattern.
 * Every other [OmakeyTheme] field (text color, pressed/special key backgrounds, suggestion bar,
 * `isDark`) is derived automatically from those 4 via simple luminance rules in
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
                TextButton(onClick = close) { Text(text = "Cancel") }
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
                ThemePreviewMock(previewTheme, showKeyBackgrounds = layoutSettings.showKeyBackgrounds, interactive = true)
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

            ThemeEditCarousel(
                pages = editPages,
                modifier = Modifier.weight(1f),
            )

            Button(onClick = { showSaveNamePrompt = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = "Save theme")
            }
        }
    }

    if (showDiscardPrompt) {
        AlertDialog(
            onDismissRequest = { showDiscardPrompt = false },
            title = { Text(text = "Discard changes?") },
            text = { Text(text = "Your changes to this theme haven't been saved.") },
            confirmButton = { TextButton(onClick = onClose) { Text(text = "Discard") } },
            dismissButton = { TextButton(onClick = { showDiscardPrompt = false }) { Text(text = "Keep editing") } },
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
        dev.omakey.core.theme.GridBorderWidth.SM to "SM",
        dev.omakey.core.theme.GridBorderWidth.MD to "MD",
        dev.omakey.core.theme.GridBorderWidth.LG to "LG",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Thickness", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.width(240.dp)) {
            widthOptions.forEachIndexed { index, (width, label) ->
                SegmentedButton(
                    selected = width == selected,
                    onClick = { onSelect(width) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = widthOptions.size),
                    icon = {},
                    label = { Text(label) },
                )
            }
        }
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
internal fun ThemePreviewMock(
    theme: OmakeyTheme,
    showKeyBackgrounds: Boolean = false,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    homeRowTinted: Boolean = true,
    alwaysShowUppercaseLetters: Boolean = true,
    edgePadding: Boolean = false,
    showSecondarySymbols: Boolean = false,
    // Keys respond to touch: a held key fills with the key tap colour, and Shift steps through
    // off → shift → caps lock, which is where the accent shows. Nothing is typed.
    interactive: Boolean = false,
) {
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }
    var rowsCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val ancestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { rowsCoordinates } }
    val keyBounds = remember { HashMap<Int, androidx.compose.ui.geometry.Rect>() }
    var pressedCode by remember { mutableStateOf<Int?>(null) }
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
                                val code = keyBounds.entries.firstOrNull { it.value.contains(down.position) }?.key
                                pressedCode = code
                                val up = waitForUpOrCancellation()
                                pressedCode = null
                                if (up != null && code == dev.omakey.core.layout.SpecialKeyCode.SHIFT) {
                                    when {
                                        capsLockOn -> { capsLockOn = false; shiftOn = false }
                                        shiftOn -> capsLockOn = true
                                        else -> shiftOn = true
                                    }
                                }
                            }
                        }
                },
        ) {
        KeyboardLocale.Default.letterLayout.rows.forEachIndexed { rowIndex, row ->
            dev.omakey.app.keyboard.ui.KeyRowView(
                rowKeys = row.keys,
                rowHeightDp = rowHeightDp,
                shiftOn = shiftOn,
                theme = theme,
                accessibleMode = false,
                showKeyBackgrounds = showKeyBackgrounds,
                pressedKeyCode = pressedCode,
                capsLockOn = capsLockOn,
                // Matches KeyGrid's own homeRowIndex for QwertyEnUS (see KeyboardRoot.kt) — the
                // ASDFGHJKL row (index 1), not the ZXCVBNM/shift row (real bug, fixed: this
                // preview had it one row too low).
                isHomeRow = rowIndex == KeyboardLocale.Default.letterLayout.homeRow,
                homeRowTinted = homeRowTinted,
                onKeyTap = {},
                ancestorCoordinates = if (interactive) ancestor else noOpAncestor,
                onBoundsMeasured = { measured -> measured.forEach { (code, _, rect) -> keyBounds[code] = rect } },
                fontFamily = fontFamily,
                alwaysShowUppercaseLetters = alwaysShowUppercaseLetters,
                showSecondarySymbols = showSecondarySymbols,
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
                            .size(32.dp)
                            .background(field.color, RoundedCornerShape(6.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                    )
                    Text(text = field.label, style = MaterialTheme.typography.titleSmall)
                    when (field.isAuto) {
                        true -> Text(
                            text = "Automatic",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        false -> TextButton(onClick = field.onReset) { Text(text = "Reset to automatic") }
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
internal fun buildCustomTheme(
    id: String,
    name: String,
    backgroundColor: Color,
    keyColor: Color,
    /** Null derives it from [keyColor]; see the editor's `keyTapColor`. */
    keyTapColor: Color?,
    stripeColor: Color,
    spacebarColor: Color,
    accentColor: Color,
    gridBorderColor: Color,
    gridBorderWidth: dev.omakey.core.theme.GridBorderWidth,
    designedForLayoutMode: dev.omakey.core.theme.LayoutMode,
): OmakeyTheme {
    // Label colour by WCAG contrast against the keys, not by a brightness cut-off: mid-tone keys
    // are exactly where a cut-off picks the less readable one.
    val textSpec = ThemeDerivation.textOn(keyColor.toColorSpec())
    val isDark = textSpec == ThemeDerivation.LIGHT_TEXT
    val textColor = textSpec.toComposeColor()
    val nudge = if (isDark) 0.15f else -0.15f
    val smallNudge = if (isDark) 0.08f else -0.08f
    return OmakeyTheme(
        id = id,
        name = name,
        isDark = isDark,
        keyboardBackground = backgroundColor.toColorSpec(),
        keyBackground = keyColor.toColorSpec(),
        keyBackgroundPressed = (keyTapColor ?: nudgeColor(keyColor, nudge)).toColorSpec(),
        keyTextColor = textColor.toColorSpec(),
        keySpecialBackground = nudgeColor(keyColor, smallNudge).toColorSpec(),
        suggestionBarBackground = nudgeColor(backgroundColor, smallNudge).toColorSpec(),
        spacebarAccentColor = spacebarColor.toColorSpec(),
        accentColor = accentColor.toColorSpec(),
        middleRowStripeColor = stripeColor.toColorSpec(),
        gridBorderColor = gridBorderColor.toColorSpec(),
        gridBorderWidth = gridBorderWidth,
        designedForLayoutMode = designedForLayoutMode,
    )
}


private fun nudgeColor(color: Color, amount: Float): Color = Color(
    red = (color.red + amount).coerceIn(0f, 1f),
    green = (color.green + amount).coerceIn(0f, 1f),
    blue = (color.blue + amount).coerceIn(0f, 1f),
    alpha = color.alpha,
)

private fun Color.toColorSpec(): ColorSpec = ColorSpec.fromArgbInt(this.toArgb())
