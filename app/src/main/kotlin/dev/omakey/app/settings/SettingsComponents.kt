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
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.layout.DEFAULT_SPACEBAR_SIDE_KEY
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

/*
 * The reusable pieces of the settings list: section headers, the shared toggle and clickable rows,
 * the individual setting toggles, and the pickers. Split out of SettingsActivity.kt.
 */

/** Groups related settings into a labeled, visually distinct card instead of a flat list of raw
 * text headers — the previous layout put every section at the same visual weight, which read as
 * "everything is one long list" rather than a set of related groups. */
@Composable
internal fun SettingsSection(title: String, titleModifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = titleModifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            DividedColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                rowPadding = ROW_VERTICAL_PADDING,
                divider = MaterialTheme.colorScheme.outlineVariant,
                content = content,
            )
        }
    }
}

/** Space above and below every row in a section card (closed-beta feedback: settings felt
 * congested; the 5.7.0 "grouped cards" redesign): 14dp in all for a setting row, split so most of
 * it sits inside the row's own touch target — a tap near a divider still hits the row. */
private val ROW_VERTICAL_PADDING = 4.dp
private val ROW_INNER_PADDING = 10.dp

/** Space between a row's text and the switch or caret at its end. It was 0, so long descriptions
 * ran right up to the switch. */
internal val ROW_CONTROL_GAP = 16.dp

/**
 * A column that gives each child [rowPadding] above and below and draws a hairline between
 * neighbouring children, so a section card reads as a list of rows without every caller adding
 * its own dividers. A child that takes no height (a row hidden by its parent setting) gets neither
 * padding nor a divider, so hiding a row never leaves a doubled line.
 */
@Composable
private fun DividedColumn(modifier: Modifier, rowPadding: Dp, divider: Color, content: @Composable () -> Unit) {
    val lines = remember { DividerLines() }
    Layout(
        content = content,
        modifier = modifier.drawWithContent {
            drawContent()
            val stroke = 1.dp.toPx()
            for (y in lines.ys) drawLine(divider, Offset(0f, y), Offset(size.width, y), stroke)
        },
    ) { measurables, constraints ->
        val pad = rowPadding.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val shown = placeables.count { it.height > 0 }
        val height = placeables.sumOf { it.height } + pad * 2 * shown
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            val ys = ArrayList<Float>()
            var y = 0
            var first = true
            for (p in placeables) {
                if (p.height == 0) { p.placeRelative(0, y); continue }
                if (!first) ys += y.toFloat()
                first = false
                y += pad
                p.placeRelative(0, y)
                y += p.height + pad
            }
            lines.ys = ys
        }
    }
}

/** Where [DividedColumn] last placed its dividers; read at draw time, written at layout. */
private class DividerLines {
    var ys: List<Float> = emptyList()
}

/** A row's title and its one-line explanation: the title at body size, the explanation a step
 * smaller and muted so the eye lands on titles first. */
@Composable
internal fun SettingText(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        if (!description.isNullOrEmpty()) SettingDescription(description)
    }
}

/** Explanatory text under a setting, in the muted description style. */
@Composable
internal fun SettingDescription(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
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

/** Re-checked on every `ON_RESUME` (not just once) — the whole point of these two checks is to
 * reflect whatever just happened in the system Settings screen the two Setup buttons send the
 * user to, and that screen is a separate Activity this one resumes underneath when they come
 * back. */
@Composable
internal fun rememberSetupStatus(): Pair<Boolean, Boolean> {
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
internal fun SetupStatusIcon(done: Boolean) {
    Icon(
        imageVector = if (done) dev.omakey.core.icons.PhosphorLineCheckCircle else dev.omakey.core.icons.PhosphorLineWarningCircle,
        contentDescription = if (done) "Done" else "Not done yet",
        tint = androidx.compose.ui.graphics.Color(if (done) 0xFF3A9D5D else 0xFFC77B00),
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun FontPicker(fontPreferences: FontPreferences, currentFontId: String) {
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

/**
 * The six Fitzpatrick tones, each drawn as the actual toned emoji rather than a colour swatch.
 *
 * A flat colour chip would be a guess at what the font renders — tones vary between emoji fonts,
 * and the point of the setting is what lands in the message. Showing the real glyph means the
 * preview cannot disagree with the result. "Default" is the unmodified yellow, which is an absence
 * of a modifier rather than a sixth tone, and reads correctly as such when shown alongside.
 */
@Composable
internal fun EmojiSkinTonePicker(preferences: EmojiSkinTonePreferences) {
    val current by preferences.skinTone.collectAsState()
    Column(rememberSettingHighlight(SettingKey.EMOJI_SKIN_TONE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = SettingKey.EMOJI_SKIN_TONE.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        Text(
            text = "Used for hands, faces and people.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (tone in EmojiSkinTone.ALL) {
                val selected = tone == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { preferences.setSkinTone(tone) }
                        .semantics { contentDescription = tone.label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = tone.sample, fontSize = 22.sp)
                }
            }
        }
        Text(
            text = current.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AutocorrectToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        key = SettingKey.AUTOCORRECT,
        description = "Fix typos when you finish a word. Press backspace straight after to undo the fix.",
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
        description = "Tap space twice to end a sentence with a full stop.",
        checked = settings.doubleTapSpaceForPeriod,
        onCheckedChange = autocorrectPreferences::setDoubleTapSpaceForPeriod,
    )
}

@Composable
internal fun AutoSpaceAfterPunctuationToggle(settings: AutocorrectSettings, autocorrectPreferences: AutocorrectPreferences) {
    SettingToggle(
        key = SettingKey.SPACE_AFTER_PUNCTUATION,
        description = "Add a space after . , ! ? : and ; so you can keep typing.",
        checked = settings.autoSpaceAfterPunctuation,
        onCheckedChange = autocorrectPreferences::setAutoSpaceAfterPunctuation,
    )
}

@Composable
internal fun NextWordPredictionToggle(predictionPreferences: PredictionPreferences) {
    val settings by predictionPreferences.settings.collectAsState()
    SettingToggle(
        key = SettingKey.NEXT_WORD_PREDICTION,
        description = "Suggest the next word after you finish one.",
        checked = settings.nextWordPredictionEnabled,
        onCheckedChange = predictionPreferences::setNextWordPredictionEnabled,
    )
}

@Composable
internal fun ImplicitLearningToggle(incognitoPreferences: IncognitoPreferences) {
    val settings by incognitoPreferences.settings.collectAsState()
    SettingToggle(
        key = SettingKey.LEARN_FROM_TYPING,
        description = "Remember names and words you type often so they stop being corrected. Stays on this phone, and never learns from password fields.",
        checked = settings.implicitLearningEnabled,
        onCheckedChange = incognitoPreferences::setImplicitLearningEnabled,
    )
}

/** "Key backgrounds" (renamed from "Boxed keys" — the old name read as a reference to Grid mode,
 * which this toggle has nothing to do with; Grid mode always shows bordered cells regardless of
 * this setting) and "Home row highlight" — both purely visual, so they live in Appearance now
 * rather than Typing. */
/** The enlarged-character bubble above a tapped key. Lives in Typing, not Appearance: it is
 * feedback about what you just typed, and it appears only while typing — grouping it with static
 * looks (themes, fonts, key shapes) put it where nobody would look for it. Distinct from
 * `GestureSettings.showKeyPopup`, the long-press accent popup, which is in Gestures. */
@Composable
internal fun TapPreviewToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.TAP_PREVIEW,
        description = "Show a bigger copy of each letter as you tap it.",
        checked = settings.showTapPreview,
        onCheckedChange = layoutPreferences::setShowTapPreview,
    )
}

/** Blank gutters down both sides of the keyboard. Fleksy shipped the same option for the same
 * reason — on a phone with no side bezel the outer keys sit where the glass curves away, which is
 * exactly where a thumb slides off. */
@Composable
internal fun SwapEmojiLanguageToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.SWAP_EMOJI_LANGUAGE,
        description = "Put the language key next to the spacebar and the emoji button in the bar above the keys.",
        checked = settings.swapEmojiAndLanguage,
        onCheckedChange = layoutPreferences::setSwapEmojiAndLanguage,
    )
}

/** Which of the bar's swipeable pages are shown (GitHub issue #10). The last one shown can't be
 * turned off — see [dev.omakey.app.keyboard.TopStripSettings]. */
@Composable
internal fun TopStripPagesSection(topStripPreferences: dev.omakey.app.keyboard.TopStripTabPreferences) {
    val settings by topStripPreferences.settings.collectAsState()
    val pages = settings.pages
    @Composable
    fun PageToggle(page: dev.omakey.app.keyboard.TopStripTab, key: SettingKey, description: String) {
        val shown = page in pages
        SettingToggle(
            key = key,
            description = if (shown && pages.size == 1) "$description At least one page stays on." else description,
            checked = shown,
            onCheckedChange = { topStripPreferences.setPageShown(page, it) },
        )
    }
    // No wrapping Column: each row is its own row of the section card.
    Text(
        text = "Pages you can swipe between above the keys. Turn off the ones you don't need.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PageToggle(
        dev.omakey.app.keyboard.TopStripTab.SUGGESTIONS,
        SettingKey.BAR_SUGGESTIONS,
        "Word suggestions, shortcuts and the language button.",
    )
    PageToggle(dev.omakey.app.keyboard.TopStripTab.NUMBERS, SettingKey.BAR_NUMBERS, "A row of numbers.")
    PageToggle(
        dev.omakey.app.keyboard.TopStripTab.TOOLS,
        SettingKey.BAR_TEXT_TOOLS,
        "Undo, redo, select all, copy, cut, paste and your clipboard.",
    )
    // Shown on every Android version; below 11 there is nothing for it to ask for, which the
    // description says rather than hiding a setting people read about.
    SettingToggle(
        key = SettingKey.AUTOFILL,
        description = "Shows logins from apps like Bitwarden above the keys. omakey can't see the passwords.",
        checked = settings.autofillSuggestions,
        onCheckedChange = topStripPreferences::setAutofillSuggestions,
    )
}

@Composable
internal fun EdgePaddingToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.EDGE_PADDING,
        description = "Leave space at the left and right edges. Helps on curved screens; keys get a little narrower.",
        checked = settings.edgePadding,
        onCheckedChange = layoutPreferences::setEdgePadding,
    )
}

@Composable
internal fun AppearanceLayoutToggles(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    // No wrapping Column: each row is its own row of the section card.
    SettingToggle(
        key = SettingKey.KEY_BACKGROUNDS,
        description = "Draw a box behind each key.",
        checked = settings.showKeyBackgrounds,
        onCheckedChange = layoutPreferences::setShowKeyBackgrounds,
    )
    SettingToggle(
        key = SettingKey.HOME_ROW_HIGHLIGHT,
        description = "Shade the middle row of letters so it stands out.",
        checked = settings.showMiddleRowStripe,
        onCheckedChange = layoutPreferences::setShowMiddleRowStripe,
    )
}

@Composable
internal fun CapitalizationToggleSection(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.ALWAYS_CAPITALS,
        description = "Show capitals on the keys all the time. Turn off to see lowercase until you press Shift.",
        checked = settings.alwaysShowUppercaseLetters,
        onCheckedChange = layoutPreferences::setAlwaysShowUppercaseLetters,
    )
}

@Composable
internal fun SecondarySymbolsToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.SECONDARY_SYMBOLS,
        description = "Show a small symbol in the corner of each key. Hold the key to type it.",
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
        description = "Show a number pad in boxes that only take numbers, like prices and phone numbers.",
        checked = settings.numberPad,
        onCheckedChange = layoutPreferences::setNumberPad,
    )
    if (!settings.numberPad) return
    Column(Modifier.padding(start = 16.dp)) {
        UnindexedSettingToggle(
            title = "Number fields",
            description = "Prices, PINs and SMS codes.",
            checked = settings.numberPadNumber,
            onCheckedChange = { layoutPreferences.setNumberPadFor(dev.omakey.core.layout.NumberPadKind.NUMBER, it) },
        )
        UnindexedSettingToggle(
            title = "Phone fields",
            description = "With letters on the digits while \"Show secondary symbols\" is on.",
            checked = settings.numberPadPhone,
            onCheckedChange = { layoutPreferences.setNumberPadFor(dev.omakey.core.layout.NumberPadKind.PHONE, it) },
        )
        UnindexedSettingToggle(
            title = "Date and time fields",
            description = "Dates like 26.10.2026 and times like 18:30.",
            checked = settings.numberPadDateTime,
            onCheckedChange = { layoutPreferences.setNumberPadFor(dev.omakey.core.layout.NumberPadKind.DATETIME, it) },
        )
    }
}

@Composable
internal fun FleksySymbolsToggle(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    SettingToggle(
        key = SettingKey.FLEKSY_SYMBOLS,
        description = "Arrange the ?123 page like Fleksy. Turn off for omakey's original arrangement.",
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
    Column(rememberSettingHighlight(SettingKey.POPUP_SYMBOLS).fillMaxWidth()) {
        Text(text = SettingKey.POPUP_SYMBOLS.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        Text(
            text = "Choose what appears when you hold any letter, like @ # \$ _ &. Leave empty to keep each key's own.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                // An 11th symbol or a repeat is refused here rather than dropped on save, so the
                // field never shows symbols the keyboard won't offer.
                if (popupSymbolsAsTyped(it) != null) {
                    text = it
                    layoutPreferences.setPopupSymbols(it)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text = "Each key's own") },
            supportingText = {
                Text(text = "${popupSymbolsAsTyped(text)?.size ?: 0} / $MAX_POPUP_SYMBOLS · no repeats")
            },
            singleLine = true,
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
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = ROW_INNER_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingText(title, description, Modifier.weight(1f))
        Spacer(Modifier.width(ROW_CONTROL_GAP))
        // The whole row toggles; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A settings row that opens something (an overlay, a picker) — the whole row is the tap target,
 * not just a trailing button, per standard Android settings-list UX (Settings app itself, most
 * system preference screens). The trailing caret is a plain visual affordance, not itself
 * interactive — it doesn't need to be, since the whole row already is. */
@Composable
internal fun ClickableSettingRow(key: SettingKey, description: String, onClick: () -> Unit) =
    UnindexedClickableSettingRow(key.title, description, onClick, rememberSettingHighlight(key))

/** A clickable row search can't find — same rule as [UnindexedSettingToggle]. */
@Composable
internal fun UnindexedClickableSettingRow(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ROW_INNER_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingText(title, description, Modifier.weight(1f))
        Spacer(Modifier.width(ROW_CONTROL_GAP))
        Icon(
            imageVector = dev.omakey.core.icons.PhosphorLineCaretRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun GestureSettingsSection(gesturePreferences: GesturePreferences) {
    val settings by gesturePreferences.settings.collectAsState()
    // No wrapping Column: each setting is its own row of the section card, so it gets a divider.
    SliderSetting(
        title = SettingKey.SWIPE_DISTANCE.title,
        description = "Shorter is quicker but easier to trigger by accident.",
        value = settings.swipeSensitivity,
        onValueChange = gesturePreferences::setSwipeSensitivity,
        range = GestureSettings.MIN_SENSITIVITY..GestureSettings.MAX_SENSITIVITY,
        low = "Short",
        high = "Long",
        modifier = rememberSettingHighlight(SettingKey.SWIPE_DISTANCE),
    )
    SettingToggle(
        key = SettingKey.LONG_PRESS_SPECIAL,
        description = "Hold a key to pick an accent or symbol, like é or !.",
        checked = settings.showKeyPopup,
        onCheckedChange = gesturePreferences::setShowKeyPopup,
    )
    SettingToggle(
        key = SettingKey.SWIPE_RIGHT_FOR_SPACE,
        description = "Swipe right on the keys to type a space.",
        checked = settings.swipeRightForSpace,
        onCheckedChange = gesturePreferences::setSwipeRightForSpace,
    )
    SettingToggle(
        key = SettingKey.SWIPE_LEFT_SPACE_STEP,
        description = "Swipe left removes the space first, then the word on the next swipe.",
        checked = settings.swipeLeftSpaceIsOwnStep,
        onCheckedChange = gesturePreferences::setSwipeLeftSpaceIsOwnStep,
    )
    SettingToggle(
        key = SettingKey.SWIPE_SPACEBAR_LANGUAGE,
        description = "Swipe left or right on the spacebar to switch language. Hold and drag it to move the cursor.",
        checked = settings.swipeSpacebarForLanguage,
        onCheckedChange = gesturePreferences::setSwipeSpacebarForLanguage,
    )
}

@Composable
internal fun FeedbackSettingsSection(preferences: HapticSoundPreferences, feedback: VibratorKeyboardFeedback) {
    val settings by preferences.settings.collectAsState()
    // One card row per setting; a sub-setting shown while its parent is on sits in the same row.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingToggle(
            key = SettingKey.HAPTIC_FEEDBACK,
            description = "Vibrate a little on each key press.",
            checked = settings.hapticEnabled,
            onCheckedChange = preferences::setHapticEnabled,
        )
        if (settings.hapticEnabled) {
            // onValueChangeFinished fires a preview tick at the strength the user just landed on —
            // dragging a "strength" slider is meaningless without feeling the result immediately.
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
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingToggle(
            key = SettingKey.KEY_SOUNDS,
            description = "Play a click on each key press.",
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
            SettingText("Sound", null)
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
internal fun AccessibleModeToggle(accessibilityPreferences: AccessibilityPreferences) {
    val enabled by accessibilityPreferences.forceAccessibleMode.collectAsState()
    SettingToggle(
        key = SettingKey.ACCESSIBLE_MODE,
        description = "Turn off swipe gestures so every key works with TalkBack. Turns on by itself when TalkBack is running.",
        checked = enabled,
        onCheckedChange = accessibilityPreferences::setForceAccessibleMode,
    )
}

/** The one manual, opt-in network call in the whole app — see [dev.omakey.core.update.UpdateChecker]'s
 * own doc and the privacy notice above it. A plain button + inline status text, not a background
 * check: nothing happens until the user taps it, every single time. */
@Composable
internal fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<UpdateCheckStatus>(UpdateCheckStatus.Idle) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The flash goes on a full-width strip around the button: the button's own opaque pill
        // would hide a background drawn under it.
        Box(rememberSettingHighlight(SettingKey.CHECK_FOR_UPDATES).fillMaxWidth().padding(6.dp)) {
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
                Text(text = if (status is UpdateCheckStatus.Checking) "Checking..." else SettingKey.CHECK_FOR_UPDATES.title)
            }
        }
        when (val current = status) {
            UpdateCheckStatus.Idle, UpdateCheckStatus.Checking -> Unit
            UpdateCheckStatus.Failed -> Text(
                text = "Couldn't check for updates — try again later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is UpdateCheckStatus.Checked -> if (current.result.updateAvailable) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Version ${current.result.latestVersion} is available.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    style = MaterialTheme.typography.bodyMedium,
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

/** Choices offered as one tap; anything else goes in the field below them. */
private val SPACEBAR_SIDE_CHOICES = listOf(DEFAULT_SPACEBAR_SIDE_KEY, ".", ",", "?", "!", "'", "-")

/** The key right of the spacebar (closed-beta request): the language's own by default, or any one
 * character. Same tile style as [EmojiSkinTonePicker]. */
@Composable
internal fun SpacebarSideKeyRow(settings: LayoutSettings, layoutPreferences: LayoutPreferences) {
    val current = settings.spacebarSideKey
    var custom by remember { mutableStateOf(current.takeIf { it !in SPACEBAR_SIDE_CHOICES }.orEmpty()) }
    Column(rememberSettingHighlight(SettingKey.SPACEBAR_SIDE_KEY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = SettingKey.SPACEBAR_SIDE_KEY.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        Text(
            text = "What the key to the right of the spacebar types. Hold it for the one it replaced.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (choice in SPACEBAR_SIDE_CHOICES) {
                val selected = choice == current
                val label = if (choice == DEFAULT_SPACEBAR_SIDE_KEY) "Auto" else choice
                Box(
                    modifier = Modifier
                        .weight(if (choice == DEFAULT_SPACEBAR_SIDE_KEY) 1.6f else 1f)
                        .height(44.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            custom = ""
                            layoutPreferences.setSpacebarSideKey(choice)
                        }
                        .semantics {
                            contentDescription = if (choice == DEFAULT_SPACEBAR_SIDE_KEY) "Language default" else "Type $choice"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = label, fontSize = if (choice == DEFAULT_SPACEBAR_SIDE_KEY) 14.sp else 20.sp)
                }
            }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = { typed ->
                // One character at most; the field refuses a second rather than saving something
                // the keyboard would then ignore.
                if (typed.isEmpty()) {
                    custom = ""
                    layoutPreferences.setSpacebarSideKey(DEFAULT_SPACEBAR_SIDE_KEY)
                } else if (sanitizeSpacebarSideKey(typed) == typed) {
                    custom = typed
                    layoutPreferences.setSpacebarSideKey(typed)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text = "Or type any one character") },
            singleLine = true,
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
    Column(modifier.fillMaxWidth().padding(vertical = ROW_INNER_PADDING)) {
        SettingText(title, description)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingDescription(low)
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range,
                modifier = Modifier.weight(1f),
            )
            SettingDescription(high)
        }
    }
}
