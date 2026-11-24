package dev.omakey.app.settings

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import dev.omakey.app.keyboard.ui.FontCatalog
import dev.omakey.core.theme.toComposeColor
import dev.omakey.core.theme.toDp
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.clipboard.ClipboardHistoryStore
import dev.omakey.core.clipboard.ClipboardPreferences
import dev.omakey.core.db.ClipboardEntity
import dev.omakey.core.db.LearnedWordEdits
import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.core.predict.PersonalLanguageModel
import dev.omakey.core.theme.OmakeyTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import dev.omakey.app.design.ButtonKind
import dev.omakey.app.design.ConfirmSheet
import dev.omakey.app.design.EmptyState
import dev.omakey.app.design.IconAction
import dev.omakey.app.design.Omakey
import dev.omakey.app.design.OmakeyButton
import dev.omakey.app.design.OmakeyField
import dev.omakey.app.design.PageHeader
import dev.omakey.app.design.RowTrailing
import dev.omakey.app.design.SettingsCard
import dev.omakey.app.design.SettingsRowLayout
import dev.omakey.core.icons.OmakeyIcons

/*
 * The full-screen overlays Settings pushes over itself: keyboard size/position, clipboard history,
 * learned words, and the keyboard test field.
 *
 * Split out of SettingsActivity.kt, which was 2382 lines. They share a shape rather than just a
 * size: each is a Surface over the whole screen with its own BackHandler, deliberately *not* a
 * separate Activity or a Material Dialog — see TestKeyboardOverlay for why a real Dialog window is
 * the wrong tool when an IME is involved.
 */

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
internal fun KeyboardSizePositionOverlay(
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

    // Row height comes from the full layout, as on the keyboard; with compact on, the preview
    // draws the rows the keyboard draws (one fewer), so it shows the real height.
    val letterLayout = KeyboardLocale.Default.letterLayout
    val rows = dev.omakey.core.layout.CompactMode.rows(letterLayout, settings.compactMode)
    val rowHeightDp = (heightDp.roundToInt() / letterLayout.rows.size)
    val keyboardTotalHeightDp = rowHeightDp * rows.size
    val maxOffsetDp = (configuration.screenHeightDp / 2f - keyboardTotalHeightDp).coerceAtLeast(0f)
    val noOpAncestor: () -> androidx.compose.ui.layout.LayoutCoordinates? = remember { { null } }

    val c = Omakey.colors
    Surface(modifier = Modifier.fillMaxSize(), color = c.ground) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PageHeader(title = "Size & position", onBack = onClose, modifier = Modifier.padding(start = 4.dp))
                SettingsCard(contentPadding = 16.dp) {
                    SettingsRowLayout(
                        title = "Height",
                        description = "Drag the bar above the keys.",
                        trailing = RowTrailing.Value("${heightDp.roundToInt()} dp"),
                        horizontalPadding = 0.dp,
                    )
                    SettingsRowLayout(
                        title = "Lift",
                        description = "Drag the grip in the middle of the keys.",
                        trailing = RowTrailing.Value(if (offsetDp.roundToInt() == 0) "Docked" else "${offsetDp.roundToInt()} dp"),
                        horizontalPadding = 0.dp,
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                OmakeyButton("Done", onClose, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth())
            }

            // Resize handle, directly above the preview — dragging it up (finger moves toward the
            // top of the screen, negative deltaPx) grows the keyboard, matching the intuitive
            // "pull the edge outward to make it bigger" gesture; dragging down shrinks it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(theme.keyboardBackground.toComposeColor())
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
                        .size(width = 44.dp, height = 5.dp)
                        .background(theme.keyTextColor.toComposeColor().copy(alpha = 0.55f), RoundedCornerShape(3.dp)),
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
                                isHomeRow = settings.showMiddleRowStripe && index == KeyboardLocale.Default.letterLayout.homeRow,
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
                        .size(width = 64.dp, height = 36.dp)
                        .background(c.ink, RoundedCornerShape(dev.omakey.app.design.Radius.control))
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
                            .size(width = 24.dp, height = 4.dp)
                            .background(c.onInk, RoundedCornerShape(2.dp)),
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

/** Derived from the model's own threshold rather than written out, so the two can't drift apart:
 * [WordDao.findUserAdded] compares against the scaled integer the `frequency` column stores. */
private val LEARNED_WORD_MIN_FREQUENCY =
    (PersonalLanguageModel.IMPLICIT_TRUST_THRESHOLD * WordEntity.COUNT_SCALE).toInt()

/** Full-screen overlay listing every word the user's own typing has taught the dictionary
 * (`WordEntity.isUserAdded`) — lets a mistakenly-learned typo (see [AutocorrectIndex.learn]: once
 * a word is "known" it's never autocorrected away again, so a typo learned before the dictionary
 * was properly seeded, or just typed too fast to catch, otherwise has no way back) be removed
 * individually or all at once, with a live prefix search since the list can get long. Deleting
 * here only touches Room — an already-running IME's in-memory `AutocorrectIndex` reloads fresh
 * from Room at its own next startup rather than being live-notified, same load-once-at-startup
 * design as the rest of the dictionary (see AGENTS.md §6). */
@Composable
internal fun ClipboardHistoryToggle(clipboardPreferences: ClipboardPreferences) {
    val settings by clipboardPreferences.settings.collectAsState()
    SettingToggle(
        key = SettingKey.SAVE_CLIPBOARD_HISTORY,
        description = "Keep what you copy to paste again. Never passwords.",
        checked = settings.historyEnabled,
        onCheckedChange = clipboardPreferences::setHistoryEnabled,
    )
}

/**
 * Everything clipboard history is currently holding, and the means to get rid of it.
 *
 * The keyboard's own clipboard panel can already delete and unpin single entries, so this is not
 * simply a second copy of that. It exists because history is the one thing omakey stores that the
 * user did not type at it, and reviewing or clearing it should not require opening a keyboard over
 * some unrelated app's text field first. "Clear everything" in particular has nowhere else to live.
 */
@Composable
internal fun ClipboardHistoryOverlay(
    clipboardDao: dev.omakey.core.db.ClipboardDao,
    clipboardHistory: ClipboardHistoryStore,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ClipboardEntity>>(emptyList()) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    suspend fun reload() {
        entries = clipboardDao.recent()
    }
    LaunchedEffect(Unit) { reload() }

    val c = Omakey.colors
    OverlayPage(title = "Clipboard history", onBack = onClose) {
        if (entries.isEmpty()) {
            item { EmptyState(OmakeyIcons.Clipboard, "What you copy shows up here, so you can paste it again.") }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (entries.size == 1) "1 item" else "${entries.size} items",
                        style = Omakey.type.label, color = c.muted, modifier = Modifier.weight(1f),
                    )
                    OmakeyButton("Clear all", { showClearAllConfirm = true }, kind = ButtonKind.Destructive, height = 40.dp)
                }
            }
            items(entries, key = { it.id }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface, RoundedCornerShape(dev.omakey.app.design.Radius.control))
                        .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (entry.pinned) {
                        androidx.compose.material3.Icon(OmakeyIcons.Pin, contentDescription = "Pinned", tint = c.muted, modifier = Modifier.size(18.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        // An image row stores a placeholder string, not the pixels.
                        text = if (entry.contentType == ClipboardEntity.TYPE_IMAGE) "Image" else entry.content,
                        style = Omakey.type.body,
                        color = c.ink,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (entry.pinned) {
                        IconAction(OmakeyIcons.Pin, "Unpin", {
                            scope.launch {
                                clipboardDao.setPinned(entry.id, false)
                                reload()
                            }
                        }, tint = c.ink)
                    }
                    IconAction(OmakeyIcons.Trash, "Remove", {
                        scope.launch {
                            clipboardHistory.delete(entry.id)
                            reload()
                        }
                    }, tint = c.muted)
                }
            }
        }
    }

    if (showClearAllConfirm) {
        ConfirmSheet(
            title = "Clear clipboard history?",
            message = "Removes everything saved, pinned items too. What you copied last stays copied.",
            confirmLabel = "Clear all",
            destructive = true,
            onConfirm = {
                showClearAllConfirm = false
                scope.launch {
                    clipboardHistory.clearAll()
                    reload()
                }
            },
            onDismiss = { showClearAllConfirm = false },
        )
    }
}

@Composable
internal fun LearnedWordsOverlay(
    wordDao: WordDao,
    /** The rules of the language a word was learned in, for checking an edit. */
    profileOf: (localeId: String) -> dev.omakey.core.locale.LanguageProfile,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Tells a running keyboard to reload, so an edit or removal applies right away rather than on
    // its next restart.
    val signal = remember { dev.omakey.core.predict.LearnedWordsSignal(context) }
    androidx.compose.runtime.DisposableEffect(signal) { onDispose { signal.close() } }
    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<WordEntity>>(emptyList()) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var wordBeingEdited by remember { mutableStateOf<WordEntity?>(null) }

    suspend fun reload() {
        words = wordDao.findUserAdded(
            query = query.trim().lowercase(),
            minFrequency = LEARNED_WORD_MIN_FREQUENCY,
        )
    }
    LaunchedEffect(query) { reload() }

    val c = Omakey.colors
    OverlayPage(title = "Learned words", onBack = onClose) {
        item {
            OmakeyField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search your words",
                leading = { androidx.compose.material3.Icon(OmakeyIcons.Search, null, tint = c.muted, modifier = Modifier.size(20.dp)) },
            )
        }
        if (words.isEmpty()) {
            item {
                EmptyState(
                    OmakeyIcons.Book,
                    if (query.isBlank()) "Names and words you type often appear here, so they stop being corrected." else "No learned word matches \"${query.trim()}\".",
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (words.size == 1) "1 word" else "${words.size} words",
                        style = Omakey.type.label, color = c.muted, modifier = Modifier.weight(1f),
                    )
                    // Only on the unfiltered list: with a query, "delete all" would be ambiguous
                    // between "all matches" and "everything".
                    if (query.isBlank()) OmakeyButton("Delete all", { showDeleteAllConfirm = true }, kind = ButtonKind.Destructive, height = 40.dp)
                }
            }
            // Lazy, one card per word: the list can hold hundreds, and every search keystroke
            // reloads it. Keyed by language *and* word: the same spelling can be learned in two
            // languages, and a duplicate key throws.
            val showLanguage = words.distinctBy { it.locale }.size > 1
            items(words, key = { "${it.locale}/${it.word}" }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface, RoundedCornerShape(dev.omakey.app.design.Radius.control))
                        .clickable(onClickLabel = "Edit") { wordBeingEdited = entry }
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry.display ?: entry.word, style = Omakey.type.row, color = c.ink, modifier = Modifier.weight(1f))
                    if (showLanguage) {
                        Text(
                            entry.locale.substringBefore('_').uppercase(),
                            style = Omakey.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = c.muted,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconAction(OmakeyIcons.Trash, "Remove ${entry.display ?: entry.word}", {
                        scope.launch {
                            wordDao.delete(entry.locale, entry.word)
                            signal.bump()
                            reload()
                        }
                    }, tint = c.muted)
                }
            }
        }
    }

    if (showDeleteAllConfirm) {
        ConfirmSheet(
            title = "Delete all learned words?",
            message = "omakey forgets every word it learned from you. Its own dictionary stays.",
            confirmLabel = "Delete all",
            destructive = true,
            onConfirm = {
                showDeleteAllConfirm = false
                scope.launch {
                    wordDao.deleteAllUserAdded()
                    signal.bump()
                    reload()
                }
            },
            onDismiss = { showDeleteAllConfirm = false },
        )
    }

    wordBeingEdited?.let { entry ->
        var editedWord by remember(entry.word) { mutableStateOf(entry.display ?: entry.word) }
        val normalised = LearnedWordEdits.normalise(editedWord, profileOf(entry.locale))
        // A change in capitals alone ("bibek" → "Bibek") is a change: it used to be lowercased away
        // and Save did nothing (5.8.0 report).
        val changed = normalised != null && (normalised.word != entry.word || normalised.display != entry.display)
        ConfirmSheet(
            title = "Edit word",
            message = null,
            confirmLabel = "Save",
            dismissLabel = "Keep as it was",
            confirmEnabled = normalised != null,
            onConfirm = {
                val edit = normalised ?: return@ConfirmSheet
                wordBeingEdited = null
                if (changed) {
                    scope.launch {
                        // Same key: only the spelling changes (renameOrMerge handles that).
                        wordDao.renameOrMerge(entry.locale, entry.word, edit.word, edit.display)
                        signal.bump()
                        reload()
                    }
                }
            },
            onDismiss = { wordBeingEdited = null },
        ) {
            OmakeyField(
                value = editedWord,
                onValueChange = { editedWord = it },
                isError = normalised == null,
                supporting = if (normalised == null) "One word, letters only" else null,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Full-screen overlay hosting a plain focused text field — focusing it brings up whichever IME
 * is currently the system default, same as focusing any text field in any real app. Only actually
 * exercises omakey if it's the active keyboard, hence the banner below when it isn't. */
@Composable
internal fun TestKeyboardOverlay(onClose: () -> Unit, onSwitchKeyboard: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val isOmakeyActive = remember { isOmakeyDefaultIme(context) }

    val c = Omakey.colors
    Surface(modifier = Modifier.fillMaxSize(), color = c.ground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageHeader(title = "Try omakey", onBack = onClose, modifier = Modifier.padding(start = 4.dp))
            if (!isOmakeyActive) {
                Row(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(dev.omakey.app.design.Radius.card)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Another keyboard is on. Switch to omakey to try it here.", style = Omakey.type.body, color = c.ink, modifier = Modifier.weight(1f))
                    OmakeyButton("Switch", onSwitchKeyboard, kind = ButtonKind.Primary, height = 40.dp)
                }
            }
            OmakeyField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                minLines = 6,
                placeholder = "Type anything",
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}

/** A full-screen page opened from a Settings page: ground colour, back arrow and title, and a
 * scrolling list of the caller's items. Used by the pages that used to be overlays. */
@Composable
private fun OverlayPage(title: String, onBack: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Omakey.colors.ground) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PageHeader(title = title, onBack = onBack, modifier = Modifier.padding(start = 4.dp)) }
            content()
        }
    }
}
