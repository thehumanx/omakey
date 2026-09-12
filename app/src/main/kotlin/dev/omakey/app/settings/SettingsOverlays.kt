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
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.clipboard.ClipboardHistoryStore
import dev.omakey.core.clipboard.ClipboardPreferences
import dev.omakey.core.db.ClipboardEntity
import dev.omakey.core.db.WordDao
import dev.omakey.core.db.WordEntity
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.layout.LayoutSettings
import dev.omakey.app.keyboard.ui.toDp
import dev.omakey.core.predict.PersonalLanguageModel
import dev.omakey.core.theme.OmakeyTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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

    val rows = KeyboardLocale.Default.letterLayout.rows
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
        title = "Save clipboard history",
        description = "Keeps what you copy while the keyboard is open, so you can paste it again " +
            "from the clipboard panel. Clips an app marks as sensitive (passwords, mostly) are " +
            "never saved, and nothing is saved while incognito is on or a password field is " +
            "focused. Turning this off stops new entries — it doesn't delete what's already there.",
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
                Text(text = "Clipboard history", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text(text = "Close") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (entries.isEmpty()) "Nothing saved" else "${entries.size} item(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { showClearAllConfirm = true }) { Text(text = "Clear all") }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            // An image row stores a placeholder string, not the pixels — showing
                            // the file path instead would be noise the user can't act on.
                            text = if (entry.contentType == ClipboardEntity.TYPE_IMAGE) {
                                "Image"
                            } else {
                                entry.content
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        if (entry.pinned) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        clipboardDao.setPinned(entry.id, false)
                                        reload()
                                    }
                                },
                            ) { Text(text = "Unpin") }
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    clipboardHistory.delete(entry.id)
                                    reload()
                                }
                            },
                        ) { Text(text = "Remove") }
                    }
                }
            }
        }
    }

    if (showClearAllConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(text = "Clear clipboard history?") },
            text = {
                Text(
                    text = "Removes every saved entry, pinned ones included, along with any copied " +
                        "images stored on this device. Your current clipboard itself isn't affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllConfirm = false
                        scope.launch {
                            clipboardHistory.clearAll()
                            reload()
                        }
                    },
                ) { Text(text = "Clear all") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text(text = "Cancel") }
            },
        )
    }
}

@Composable
internal fun LearnedWordsOverlay(wordDao: WordDao, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
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
