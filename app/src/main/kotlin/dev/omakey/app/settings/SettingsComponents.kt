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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
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
import androidx.compose.material3.SegmentedButton
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.DEFAULT_SPACEBAR_SIDE_KEY
import dev.omakey.core.layout.EMOJI_SLOT_DEFAULT
import dev.omakey.core.layout.EMOJI_SLOT_LANGUAGE
import dev.omakey.core.layout.sanitizeEmojiSlotKey
import dev.omakey.core.layout.MAX_POPUP_SYMBOLS
import dev.omakey.core.layout.sanitizeSpacebarSideKey
import dev.omakey.core.layout.popupSymbolsAsTyped
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.AutocorrectSettings
import dev.omakey.core.predict.IncognitoPreferences
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.theme.AccessibilityPreferences
import dev.omakey.core.theme.FontPreferences
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import dev.omakey.app.design.ButtonKind
import dev.omakey.app.design.ChoiceTile
import dev.omakey.app.design.DragReorderList
import dev.omakey.app.design.Figtree
import dev.omakey.app.design.Omakey
import dev.omakey.app.design.OmakeyButton
import dev.omakey.app.design.OmakeyField
import dev.omakey.app.design.OmakeySlider
import dev.omakey.app.design.RowTrailing
import dev.omakey.app.design.SectionLabel
import dev.omakey.app.design.Segmented
import dev.omakey.app.design.SettingsCard
import dev.omakey.app.design.SettingsRowLayout
import dev.omakey.core.icons.OmakeyIcons

/*
 * The reusable pieces of the settings list: section headers, the shared toggle and clickable rows,
 * the individual setting toggles, and the pickers. Split out of SettingsActivity.kt.
 */

/** A group of related settings: its label, then a white card of rows (design: Components → Card). */
@Composable
internal fun SettingsSection(title: String, titleModifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title, titleModifier)
        SettingsCard(contentPadding = 16.dp, dividerInset = 0.dp, content = content)
    }
}

/** Space between a row's text and the switch or caret at its end. */
internal val ROW_CONTROL_GAP = 16.dp

/** A setting that isn't a single row (pickers, fields): its own padding inside the card. */
@Composable
internal fun SettingBlock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

/** A row's title and its one-line explanation: Row type over muted Body. */
@Composable
internal fun SettingText(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = Omakey.type.row, color = Omakey.colors.ink)
        if (!description.isNullOrEmpty()) SettingDescription(description)
    }
}

/** Explanatory text under a setting, in the muted description style. */
@Composable
internal fun SettingDescription(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = Omakey.type.body, color = Omakey.colors.muted, modifier = modifier)
}

/** A section card from the index. A picker section with no row title of its own (Theme, Font…)
 * flashes its header when search opens it. */
@Composable
internal fun SettingsSection(key: SettingsSectionKey, content: @Composable () -> Unit) {
    val headerEntry = SettingKey.entries.firstOrNull { it.sectionLevel && it.section == key }
    SettingsSection(
        title = key.title,
        titleModifier = if (headerEntry != null) rememberSettingHighlight(headerEntry) else Modifier,
        content = content,
    )
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

/** Re-checked on every resume (the system's keyboard list is a separate screen) and whenever the
 * system's keyboard settings change. Choosing a keyboard from the switcher doesn't pause this
 * screen, so a resume alone missed it and the card kept saying "Make omakey your keyboard". */
@Composable
internal fun rememberSetupStatus(): Pair<Boolean, Boolean> {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(isOmakeyEnabled(context)) }
    var isDefault by remember { mutableStateOf(isOmakeyDefaultIme(context)) }
    val refresh = {
        isEnabled = isOmakeyEnabled(context)
        isDefault = isOmakeyDefaultIme(context)
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val settingsObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, settingsObserver)
        resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, settingsObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            resolver.unregisterContentObserver(settingsObserver)
        }
    }
    return isEnabled to isDefault
}

/** "Ag" in each font, on a tonal tile; the chosen font has an ink ring and a check. */
@Composable
internal fun FontPicker(fontPreferences: FontPreferences, currentFontId: String) {
    SettingBlock {
        FontCatalog.displayNames.forEach { (id, name) ->
            val selected = id == currentFontId
            ChoiceTile(selected = selected, onClick = { fontPreferences.setFont(id) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(text = "Ag", fontFamily = FontCatalog.resolve(id) ?: Figtree, fontSize = 22.sp, color = Omakey.colors.ink, modifier = Modifier.width(36.dp))
                    Text(text = name, style = Omakey.type.row, color = Omakey.colors.ink, modifier = Modifier.weight(1f))
                    if (selected) Icon(OmakeyIcons.Check, contentDescription = "Chosen", tint = Omakey.colors.ink, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * The six Fitzpatrick tones, each drawn as the actual toned emoji rather than a colour swatch.
 *
 * A flat colour chip would be a guess at what the font renders — tones vary between emoji fonts,
 * and the point of the setting is what lands in the message. "Default" is the unmodified yellow.
 */
@Composable
internal fun EmojiSkinTonePicker(preferences: EmojiSkinTonePreferences) {
    val current by preferences.skinTone.collectAsState()
    SettingBlock(rememberSettingHighlight(SettingKey.EMOJI_SKIN_TONE)) {
        SettingText(SettingKey.EMOJI_SKIN_TONE.title, "For hands, faces and people. Now: ${current.label.lowercase()}.")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tone in EmojiSkinTone.ALL) {
                ChoiceTile(
                    selected = tone == current,
                    onClick = { preferences.setSkinTone(tone) },
                    label = tone.label,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text(text = tone.sample, fontSize = 22.sp) }
            }
        }
    }
}

@Composable
internal fun AutocorrectToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        key = SettingKey.AUTOCORRECT,
        description = "Fix clear typos as you finish a word. Backspace undoes a fix.",
        checked = settings.autocorrectEnabled,
        onCheckedChange = autocorrectPreferences::setAutocorrectEnabled,
    )
}

@Composable
internal fun AutoCapitalizeToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        key = SettingKey.AUTO_CAPITALIZE,
        description = "Start each sentence with a capital letter.",
        checked = settings.autoCapitalizeEnabled,
        onCheckedChange = autocorrectPreferences::setAutoCapitalizeEnabled,
    )
}

@Composable
internal fun DoubleTapSpaceForPeriodToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        key = SettingKey.DOUBLE_TAP_PERIOD,
        description = "Tap space twice to end a sentence.",
        checked = settings.doubleTapSpaceForPeriod,
        onCheckedChange = autocorrectPreferences::setDoubleTapSpaceForPeriod,
    )
}

@Composable
internal fun AutoSpaceAfterPunctuationToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        key = SettingKey.SPACE_AFTER_PUNCTUATION,
        description = "Add a space after . , ! ? : and ;",
        checked = settings.autoSpaceAfterPunctuation,
        onCheckedChange = autocorrectPreferences::setAutoSpaceAfterPunctuation,
    )
}

@Composable
internal fun NextWordPredictionToggle(predictionPreferences: PredictionPreferences) {
    val settings by predictionPreferences.settings.collectAsState()
    SettingToggle(
        key = SettingKey.NEXT_WORD_PREDICTION,
        description = "Suggest the next word after a space.",
        checked = settings.nextWordPredictionEnabled,
        onCheckedChange = predictionPreferences::setNextWordPredictionEnabled,
    )
}

@Composable
internal fun ImplicitLearningToggle(incognitoPreferences: IncognitoPreferences) {
    val settings by incognitoPreferences.settings.collectAsState()
    SettingToggle(
        key = SettingKey.LEARN_FROM_TYPING,
        description = "Remember names and words you use. Never from password fields.",
        checked = settings.implicitLearningEnabled,
        onCheckedChange = incognitoPreferences::setImplicitLearningEnabled,
    )
}

/** The enlarged-character bubble above a tapped key. Distinct from `GestureSettings.showKeyPopup`,
 * the long-press accent popup, which is in Gestures. */
@Composable
internal fun TapPreviewStylePicker(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    val options = listOf(
        dev.omakey.core.layout.TapPreviewStyle.CLASSIC to "Classic",
        dev.omakey.core.layout.TapPreviewStyle.SLIM to "Slim",
        dev.omakey.core.layout.TapPreviewStyle.LIFT to "Lift",
        dev.omakey.core.layout.TapPreviewStyle.OFF to "Off",
    )
    val description = when (settings.tapPreviewStyle) {
        dev.omakey.core.layout.TapPreviewStyle.CLASSIC -> "A bigger copy of the letter above your finger."
        dev.omakey.core.layout.TapPreviewStyle.SLIM -> "A small copy of the letter, just above the key."
        dev.omakey.core.layout.TapPreviewStyle.LIFT -> "The key rises and grows a little."
        dev.omakey.core.layout.TapPreviewStyle.OFF -> "Only the key you press lights up."
    }
    SettingBlock(rememberSettingHighlight(SettingKey.TAP_PREVIEW)) {
        SettingText(SettingKey.TAP_PREVIEW.title, description)
        Segmented(options, settings.tapPreviewStyle, layoutPreferences::setTapPreviewStyle)
    }
}

/**
 * Bar pages (GitHub issue #10): Suggestions first, with the password-manager switch under it since
 * that's where the logins appear, then the other pages in a list you reorder by dragging. The last
 * page shown can't be turned off — see [dev.omakey.app.keyboard.TopStripSettings].
 */
@Composable
internal fun BarPagesPage(topStripPreferences: dev.omakey.app.keyboard.TopStripTabPreferences) {
    val settings by topStripPreferences.settings.collectAsState()
    val pages = settings.pages
    val context = LocalContext.current
    val lastOne = pages.size == 1

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingDescription("The pages you swipe between above the keys. Hold a page and drag it to change the order.", Modifier.padding(horizontal = 4.dp))
        SettingsCard(dividerInset = 70.dp) {
            val suggestionsShown = dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS in pages
            SettingsRowLayout(
                title = SettingKey.BAR_SUGGESTIONS.title,
                description = if (suggestionsShown && lastOne) "Always first. One page stays on." else "Always first",
                icon = OmakeyIcons.Type,
                trailing = RowTrailing.Switch(suggestionsShown),
                modifier = rememberSettingHighlight(SettingKey.BAR_SUGGESTIONS).toggleable(
                    value = suggestionsShown, role = Role.Switch,
                    onValueChange = { topStripPreferences.setPageShown(dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS, it) },
                ),
            )
            Box(Modifier.padding(start = 54.dp)) {
                SettingsRowLayout(
                    title = SettingKey.AUTOFILL.title,
                    description = "Logins from apps like Bitwarden. omakey can't see the passwords.",
                    trailing = RowTrailing.Switch(settings.autofillSuggestions),
                    modifier = rememberSettingHighlight(SettingKey.AUTOFILL).toggleable(
                        value = settings.autofillSuggestions, role = Role.Switch,
                        onValueChange = topStripPreferences::setAutofillSuggestions,
                    ),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Then, in this order")
            val movable = settings.movableOrder
            DragReorderList(
                items = movable,
                label = { barPageKey(it).title },
                onMove = { from, to -> topStripPreferences.movePage(movable[from], to - from) },
            ) { page, _ ->
                val key = barPageKey(page)
                val shown = page in pages
                Row(
                    rememberSettingHighlight(key).fillMaxWidth().toggleable(
                        value = shown, role = Role.Switch,
                        onValueChange = { topStripPreferences.setPageShown(page, it) },
                    ).padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(OmakeyIcons.Grip, contentDescription = null, tint = Omakey.colors.subtle, modifier = Modifier.size(18.dp))
                    SettingsRowLayout(
                        title = key.title,
                        description = when {
                            shown && lastOne -> "One page stays on."
                            page == dev.omakey.app.keyboard.TopStripTab.SNIPPETS -> null
                            else -> null
                        },
                        icon = barPageIcon(page),
                        trailing = RowTrailing.Switch(shown),
                        horizontalPadding = 12.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            OmakeyButton(
                text = "Manage snippets",
                icon = OmakeyIcons.Snippet,
                onClick = { openSnippets(context) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Quick access: every tile with a switch, in the order the panel shows them; hold to reorder. */
@Composable
internal fun QuickAccessTilesPage() {
    val context = LocalContext.current
    val prefs = remember { dev.omakey.core.layout.QuickAccessPreferences(context) }
    androidx.compose.runtime.DisposableEffect(prefs) { onDispose { prefs.close() } }
    val tiles by prefs.tiles.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = rememberSettingHighlight(SettingKey.QUICK_ACCESS_TILES)) {
        SettingDescription("The tiles behind the button at the left of the bar. Hold a tile and drag it to change the order.", Modifier.padding(horizontal = 4.dp))
        DragReorderList(
            items = tiles.order,
            label = { quickTileName(it) },
            onMove = { from, to -> prefs.move(from, to) },
        ) { tile, _ ->
            val shown = tile !in tiles.hidden
            Row(
                Modifier.fillMaxWidth().toggleable(value = shown, role = Role.Switch, onValueChange = { prefs.setShown(tile, it) }).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(OmakeyIcons.Grip, contentDescription = null, tint = Omakey.colors.subtle, modifier = Modifier.size(18.dp))
                SettingsRowLayout(
                    title = quickTileName(tile),
                    description = quickTileNote(tile, shown && tiles.shown.size == 1),
                    icon = quickTileIcon(tile),
                    trailing = RowTrailing.Switch(shown),
                    horizontalPadding = 12.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun quickTileName(t: dev.omakey.core.layout.QuickTileId) = when (t) {
    dev.omakey.core.layout.QuickTileId.INCOGNITO -> "Incognito"
    dev.omakey.core.layout.QuickTileId.ONE_HANDED -> "One-handed"
    dev.omakey.core.layout.QuickTileId.FLOATING -> "Floating"
    dev.omakey.core.layout.QuickTileId.RESIZE -> "Resize"
    dev.omakey.core.layout.QuickTileId.GRID -> "Grid layout"
    dev.omakey.core.layout.QuickTileId.COMPACT -> "Compact"
    dev.omakey.core.layout.QuickTileId.SPLIT -> "Split"
    dev.omakey.core.layout.QuickTileId.THEME -> "Theme"
    dev.omakey.core.layout.QuickTileId.SETTINGS -> "Settings"
    dev.omakey.core.layout.QuickTileId.LANGUAGE -> "Language"
    dev.omakey.core.layout.QuickTileId.AUTOCORRECT -> "Autocorrect"
    dev.omakey.core.layout.QuickTileId.CLIPBOARD -> "Clipboard"
    dev.omakey.core.layout.QuickTileId.EMOJI -> "Emoji"
}

private fun quickTileNote(t: dev.omakey.core.layout.QuickTileId, lastOne: Boolean): String? = when {
    lastOne -> "One tile stays on."
    t == dev.omakey.core.layout.QuickTileId.LANGUAGE -> "Shown with two or more languages"
    t == dev.omakey.core.layout.QuickTileId.AUTOCORRECT -> "Turn autocorrect on or off"
    else -> null
}

private fun quickTileIcon(t: dev.omakey.core.layout.QuickTileId) = when (t) {
    dev.omakey.core.layout.QuickTileId.INCOGNITO -> OmakeyIcons.Incognito
    dev.omakey.core.layout.QuickTileId.ONE_HANDED -> OmakeyIcons.OneHanded
    dev.omakey.core.layout.QuickTileId.FLOATING -> OmakeyIcons.Floating
    dev.omakey.core.layout.QuickTileId.RESIZE -> OmakeyIcons.Resize
    dev.omakey.core.layout.QuickTileId.GRID -> OmakeyIcons.LayoutGrid
    dev.omakey.core.layout.QuickTileId.COMPACT -> OmakeyIcons.LayoutRows
    dev.omakey.core.layout.QuickTileId.SPLIT -> OmakeyIcons.Split
    dev.omakey.core.layout.QuickTileId.THEME -> OmakeyIcons.Theme
    dev.omakey.core.layout.QuickTileId.SETTINGS -> OmakeyIcons.Settings
    dev.omakey.core.layout.QuickTileId.LANGUAGE -> OmakeyIcons.Globe
    dev.omakey.core.layout.QuickTileId.AUTOCORRECT -> OmakeyIcons.Check
    dev.omakey.core.layout.QuickTileId.CLIPBOARD -> OmakeyIcons.Clipboard
    dev.omakey.core.layout.QuickTileId.EMOJI -> OmakeyIcons.Emoji
}

private fun barPageKey(page: dev.omakey.app.keyboard.TopStripTab): SettingKey = when (page) {
    dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS -> SettingKey.BAR_SUGGESTIONS
    dev.omakey.app.keyboard.TopStripTab.SNIPPETS -> SettingKey.BAR_SNIPPETS
    dev.omakey.app.keyboard.TopStripTab.NUMBERS -> SettingKey.BAR_NUMBERS
    dev.omakey.app.keyboard.TopStripTab.TOOLS -> SettingKey.BAR_TEXT_TOOLS
}

private fun barPageIcon(page: dev.omakey.app.keyboard.TopStripTab) = when (page) {
    dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS -> OmakeyIcons.Type
    dev.omakey.app.keyboard.TopStripTab.SNIPPETS -> OmakeyIcons.Snippet
    dev.omakey.app.keyboard.TopStripTab.NUMBERS -> OmakeyIcons.Hash
    dev.omakey.app.keyboard.TopStripTab.TOOLS -> OmakeyIcons.Undo
}

/** Blank gutters down both sides of the keyboard, for phones whose glass curves away at the edges. */
@Composable
internal fun EdgePaddingToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.EDGE_PADDING,
        description = "Leave space at the edges, for curved screens.",
        checked = settings.edgePadding,
        onCheckedChange = layoutPreferences::setEdgePadding,
    )
}

@Composable
internal fun AppearanceLayoutToggles(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.KEY_BACKGROUNDS,
        description = "A box behind each key.",
        checked = settings.showKeyBackgrounds,
        onCheckedChange = layoutPreferences::setShowKeyBackgrounds,
    )
    SettingToggle(
        key = SettingKey.HOME_ROW_HIGHLIGHT,
        description = "Shade the middle row of letters.",
        checked = settings.showMiddleRowStripe,
        onCheckedChange = layoutPreferences::setShowMiddleRowStripe,
    )
}

/** Split-keyboard detail: only means something on a wide screen, so it sits with Size & position. */
@Composable
internal fun SplitDuplicateKeysToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.SPLIT_DUPLICATE_KEYS,
        description = "When split, show G and V on both halves.",
        checked = settings.splitDuplicateKeys,
        onCheckedChange = layoutPreferences::setSplitDuplicateKeys,
    )
}

@Composable
internal fun CapitalizationToggleSection(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.ALWAYS_CAPITALS,
        description = "Off shows lowercase until you press Shift.",
        checked = settings.alwaysShowUppercaseLetters,
        onCheckedChange = layoutPreferences::setAlwaysShowUppercaseLetters,
    )
}

@Composable
internal fun SecondarySymbolsToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.SECONDARY_SYMBOLS,
        description = "A small symbol in each key's corner. Hold to type it.",
        checked = settings.showSecondarySymbols,
        onCheckedChange = layoutPreferences::setShowSecondarySymbols,
    )
}

/** The number pad, with a switch per kind of field under it — shown only while the pad is on,
 * since they mean nothing without it. */
@Composable
internal fun NumberPadSection(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.NUMBER_PAD,
        description = "In boxes that only take numbers, like prices.",
        checked = settings.numberPad,
        onCheckedChange = layoutPreferences::setNumberPad,
    )
    if (!settings.numberPad) return
    UnindexedSettingToggle(
        title = "Number fields",
        description = "Prices, PINs and SMS codes.",
        checked = settings.numberPadNumber,
        onCheckedChange = { layoutPreferences.setNumberPadFor(dev.omakey.core.layout.NumberPadKind.NUMBER, it) },
    )
    UnindexedSettingToggle(
        title = "Phone fields",
        description = "Letters on the digits when secondary symbols are on.",
        checked = settings.numberPadPhone,
        onCheckedChange = { layoutPreferences.setNumberPadFor(dev.omakey.core.layout.NumberPadKind.PHONE, it) },
    )
    UnindexedSettingToggle(
        title = "Date and time fields",
        description = "Like 26.10.2026 and 18:30.",
        checked = settings.numberPadDateTime,
        onCheckedChange = { layoutPreferences.setNumberPadFor(dev.omakey.core.layout.NumberPadKind.DATETIME, it) },
    )
}

@Composable
internal fun FleksySymbolsToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.FLEKSY_SYMBOLS,
        description = "Arrange the ?123 page the way Fleksy does.",
        checked = settings.fleksySymbols,
        onCheckedChange = layoutPreferences::setFleksySymbols,
    )
}

/** The user's own long-press symbols. Saved on every edit, since the keyboard typing into this
 * field is the one that shows the result; the field keeps what was typed (spaces included) while
 * the stored value is the parsed symbols. */
@Composable
internal fun PopupSymbolsField(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    var text by remember { mutableStateOf(settings.popupSymbols.joinToString(" ")) }
    SettingBlock(rememberSettingHighlight(SettingKey.POPUP_SYMBOLS)) {
        SettingText(SettingKey.POPUP_SYMBOLS.title, "What appears when you hold any letter. Empty keeps each key's own.")
        OmakeyField(
            value = text,
            onValueChange = {
                // An 11th symbol or a repeat is refused here rather than dropped on save, so the
                // field never shows symbols the keyboard won't offer.
                if (popupSymbolsAsTyped(it) != null) {
                    text = it
                    layoutPreferences.setPopupSymbols(it)
                }
            },
            placeholder = "Each key's own",
            supporting = "${popupSymbolsAsTyped(text)?.size ?: 0} of $MAX_POPUP_SYMBOLS · no repeats",
        )
    }
}

@Composable
internal fun SettingToggle(key: SettingKey, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) =
    UnindexedSettingToggle(key.title, description, checked, onCheckedChange, rememberSettingHighlight(key))

/** A toggle search can't find. Only for rows that can't have a [SettingKey]: ones shown only
 * while another setting is on (their words are keywords of the parent), and per-language rows.
 * An always-shown setting uses the [SettingKey] overload so search finds it. */
@Composable
internal fun UnindexedSettingToggle(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // The whole row toggles; the switch only shows the state.
    SettingsRowLayout(
        title = title,
        description = description,
        trailing = RowTrailing.Switch(checked, enabled),
        enabled = enabled,
        horizontalPadding = 0.dp,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/** A settings row that opens something: the whole row is the tap target, the chevron is a cue. */
@Composable
internal fun ClickableSettingRow(key: SettingKey, description: String, onClick: () -> Unit) =
    UnindexedClickableSettingRow(key.title, description, onClick, rememberSettingHighlight(key))

/** A clickable row search can't find — same rule as [UnindexedSettingToggle]. */
@Composable
internal fun UnindexedClickableSettingRow(title: String, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsRowLayout(
        title = title,
        description = description,
        trailing = RowTrailing.Chevron,
        horizontalPadding = 0.dp,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
internal fun GestureSettingsSection(gesturePreferences: GesturePreferences) {
    val settings by gesturePreferences.settings.collectAsState()
    SliderSetting(
        title = SettingKey.SWIPE_DISTANCE.title,
        description = "Shorter is quicker, but easier to set off by accident.",
        value = settings.swipeSensitivity,
        onValueChange = gesturePreferences::setSwipeSensitivity,
        range = GestureSettings.MIN_SENSITIVITY..GestureSettings.MAX_SENSITIVITY,
        low = "Short",
        high = "Long",
        modifier = rememberSettingHighlight(SettingKey.SWIPE_DISTANCE),
    )
    SettingToggle(
        key = SettingKey.LONG_PRESS_SPECIAL,
        description = "Hold a key for accents and symbols, like é.",
        checked = settings.showKeyPopup,
        onCheckedChange = gesturePreferences::setShowKeyPopup,
    )
    SettingToggle(
        key = SettingKey.SWIPE_RIGHT_FOR_SPACE,
        description = "Swipe right across the keys to type a space.",
        checked = settings.swipeRightForSpace,
        onCheckedChange = gesturePreferences::setSwipeRightForSpace,
    )
    SettingToggle(
        key = SettingKey.SWIPE_LEFT_SPACE_STEP,
        description = "Swipe left takes the space first, then the word.",
        checked = settings.swipeLeftSpaceIsOwnStep,
        onCheckedChange = gesturePreferences::setSwipeLeftSpaceIsOwnStep,
    )
    SettingToggle(
        key = SettingKey.SWIPE_SPACEBAR_LANGUAGE,
        description = "Swipe the spacebar sideways to switch language.",
        checked = settings.swipeSpacebarForLanguage,
        onCheckedChange = gesturePreferences::setSwipeSpacebarForLanguage,
    )
}

@Composable
internal fun FeedbackSettingsSection(preferences: HapticSoundPreferences, feedback: VibratorKeyboardFeedback) {
    val settings by preferences.settings.collectAsState()
    // A sub-setting shown while its parent is on sits in the same card, right under it.
    SettingToggle(
        key = SettingKey.HAPTIC_FEEDBACK,
        description = "A light tick on each key press.",
        checked = settings.hapticEnabled,
        onCheckedChange = preferences::setHapticEnabled,
    )
    if (settings.hapticEnabled) {
        // onValueChangeFinished plays a tick at the strength just chosen: a strength is
        // meaningless without feeling it.
        SliderSetting(
            title = "Strength",
            description = null,
            value = settings.hapticStrength,
            onValueChange = preferences::setHapticStrength,
            onValueChangeFinished = { feedback.onKeyPress() },
            range = HapticSoundSettings.MIN_HAPTIC_STRENGTH..HapticSoundSettings.MAX_HAPTIC_STRENGTH,
            low = "Light",
            high = "Strong",
        )
    }
    SettingToggle(
        key = SettingKey.KEY_SOUNDS,
        description = "A click on each key press.",
        checked = settings.soundEnabled,
        onCheckedChange = preferences::setSoundEnabled,
    )
    if (settings.soundEnabled) {
        SliderSetting(
            title = "Volume",
            description = null,
            value = settings.soundVolume,
            onValueChange = preferences::setSoundVolume,
            onValueChangeFinished = { feedback.onKeyPress() },
            range = HapticSoundSettings.MIN_SOUND_VOLUME..HapticSoundSettings.MAX_SOUND_VOLUME,
            low = "Quiet",
            high = "Loud",
        )
        SoundChoicePicker(preferences, settings.soundChoice, feedback)
    }
}

/** Tap a sound to choose it and hear it: a sound is meaningless to pick from its name alone. */
@Composable
private fun SoundChoicePicker(preferences: HapticSoundPreferences, currentChoice: String, feedback: VibratorKeyboardFeedback) {
    SettingBlock {
        SettingText("Sound", "Tap one to hear it.")
        SoundCatalog.displayNames.forEach { (id, name) ->
            val selected = id == currentChoice
            ChoiceTile(
                selected = selected,
                onClick = {
                    preferences.setSoundChoice(id)
                    feedback.onKeyPress()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = name, style = Omakey.type.row, color = Omakey.colors.ink, modifier = Modifier.weight(1f))
                    if (selected) Icon(OmakeyIcons.Check, contentDescription = "Chosen", tint = Omakey.colors.ink, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
internal fun AccessibleModeToggle(accessibilityPreferences: AccessibilityPreferences) {
    val enabled by accessibilityPreferences.forceAccessibleMode.collectAsState()
    SettingToggle(
        key = SettingKey.ACCESSIBLE_MODE,
        description = "Every key works with TalkBack. Turns on by itself with it.",
        checked = enabled,
        onCheckedChange = accessibilityPreferences::setForceAccessibleMode,
    )
}

/** The one manual, opt-in network call in the whole app — see [dev.omakey.core.update.UpdateChecker].
 * Nothing happens until the user taps it, every single time. */
@Composable
internal fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateCheckStatus>(UpdateCheckStatus.Idle) }

    SettingBlock(rememberSettingHighlight(SettingKey.CHECK_FOR_UPDATES)) {
        val line = when (val current = status) {
            UpdateCheckStatus.Idle -> null
            UpdateCheckStatus.Checking -> "Checking…"
            UpdateCheckStatus.Failed -> "Couldn't check. Try again later."
            is UpdateCheckStatus.Checked ->
                if (current.result.updateAvailable) "Version ${current.result.latestVersion} is out." else "You're up to date."
        }
        if (line != null) SettingDescription(line)
        val available = (status as? UpdateCheckStatus.Checked)?.result?.takeIf { it.updateAvailable }
        if (available != null) {
            OmakeyButton(
                text = "See what's new",
                kind = ButtonKind.Primary,
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(available.releaseUrl))) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OmakeyButton(
                text = SettingKey.CHECK_FOR_UPDATES.title,
                enabled = status !is UpdateCheckStatus.Checking,
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private sealed interface UpdateCheckStatus {
    data object Idle : UpdateCheckStatus
    data object Checking : UpdateCheckStatus
    data object Failed : UpdateCheckStatus
    data class Checked(val result: dev.omakey.core.update.UpdateCheckResult) : UpdateCheckStatus
}

/** A key choice: a word label, an icon, or the character itself. */
private sealed interface KeyChoiceLabel {
    data class Word(val text: String) : KeyChoiceLabel
    data class Glyph(val icon: androidx.compose.ui.graphics.vector.ImageVector) : KeyChoiceLabel
    data class Char(val text: String) : KeyChoiceLabel
}

/** Choices offered as one tap; anything else goes in the field below them. */
private val SPACEBAR_SIDE_CHOICES: List<Pair<String, KeyChoiceLabel>> =
    listOf(DEFAULT_SPACEBAR_SIDE_KEY to KeyChoiceLabel.Word("Auto")) +
        listOf(".", ",", "?", "!", "'", "-").map { it to KeyChoiceLabel.Char(it) }

/** The key right of the spacebar (closed-beta request): the language's own by default, or any one
 * character. */
@Composable
internal fun SpacebarSideKeyRow(settings: LayoutSettings, layoutPreferences: LayoutPreferences) = KeyChoiceRow(
    key = SettingKey.SPACEBAR_SIDE_KEY,
    description = "Hold it for the one it replaced.",
    choices = SPACEBAR_SIDE_CHOICES,
    defaultValue = DEFAULT_SPACEBAR_SIDE_KEY,
    current = settings.spacebarSideKey,
    describe = { if (it == DEFAULT_SPACEBAR_SIDE_KEY) "Language default" else "Type $it" },
    isValidCustom = { sanitizeSpacebarSideKey(it) == it },
    onChoose = layoutPreferences::setSpacebarSideKey,
)

private val EMOJI_SLOT_CHOICES: List<Pair<String, KeyChoiceLabel>> =
    listOf(EMOJI_SLOT_DEFAULT to KeyChoiceLabel.Glyph(OmakeyIcons.Emoji), EMOJI_SLOT_LANGUAGE to KeyChoiceLabel.Glyph(OmakeyIcons.Globe)) +
        listOf(",", ".", "?", "!", "'").map { it to KeyChoiceLabel.Char(it) }

/** The key left of the spacebar (user request): emoji by default, the language key, or any one
 * character. The emoji button moves up to the bar whenever its key is taken. */
@Composable
internal fun EmojiSlotKeyRow(settings: LayoutSettings, layoutPreferences: LayoutPreferences) = KeyChoiceRow(
    key = SettingKey.EMOJI_SLOT_KEY,
    description = "Emoji, switch language (with two or more), or a character. Emoji moves to the bar when it isn't.",
    choices = EMOJI_SLOT_CHOICES,
    defaultValue = EMOJI_SLOT_DEFAULT,
    current = settings.emojiSlotKey,
    describe = {
        when (it) {
            EMOJI_SLOT_DEFAULT -> "Emoji"
            EMOJI_SLOT_LANGUAGE -> "Switch language"
            else -> "Type $it"
        }
    },
    isValidCustom = { sanitizeEmojiSlotKey(it) == it && it != EMOJI_SLOT_LANGUAGE },
    onChoose = layoutPreferences::setEmojiSlotKey,
)

/** A key that can be one of a few one-tap [choices] or any one character typed in the field below. */
@Composable
private fun KeyChoiceRow(
    key: SettingKey,
    description: String,
    choices: List<Pair<String, KeyChoiceLabel>>,
    defaultValue: String,
    current: String,
    describe: (String) -> String,
    isValidCustom: (String) -> Boolean,
    onChoose: (String) -> Unit,
) {
    var custom by remember { mutableStateOf(current.takeIf { c -> choices.none { it.first == c } }.orEmpty()) }
    SettingBlock(rememberSettingHighlight(key)) {
        SettingText(key.title, description)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((choice, label) in choices) {
                ChoiceTile(
                    selected = choice == current,
                    onClick = {
                        custom = ""
                        onChoose(choice)
                    },
                    label = describe(choice),
                    modifier = Modifier.weight(if (label is KeyChoiceLabel.Word) 1.6f else 1f).height(44.dp),
                ) {
                    when (label) {
                        is KeyChoiceLabel.Word -> Text(label.text, style = Omakey.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Omakey.colors.ink)
                        is KeyChoiceLabel.Glyph -> Icon(label.icon, contentDescription = null, tint = Omakey.colors.ink, modifier = Modifier.size(20.dp))
                        is KeyChoiceLabel.Char -> Text(label.text, style = Omakey.type.row.copy(fontSize = 20.sp), color = Omakey.colors.ink)
                    }
                }
            }
        }
        OmakeyField(
            value = custom,
            onValueChange = { typed ->
                // One character at most; the field refuses a second rather than saving something
                // the keyboard would then ignore.
                if (typed.isEmpty()) {
                    custom = ""
                    onChoose(defaultValue)
                } else if (isValidCustom(typed)) {
                    custom = typed
                    onChoose(typed)
                }
            },
            placeholder = "Or type any one character",
        )
    }
}

/** A setting set with a slider: title and optional description above, the slider between its two
 * end labels below. */
@Composable
internal fun SliderSetting(
    title: String,
    description: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    low: String,
    high: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        SettingText(title, description)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(low, style = Omakey.type.caption, color = Omakey.colors.muted)
            OmakeySlider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                range = range,
                modifier = Modifier.weight(1f),
            )
            Text(high, style = Omakey.type.caption, color = Omakey.colors.muted)
        }
    }
}
