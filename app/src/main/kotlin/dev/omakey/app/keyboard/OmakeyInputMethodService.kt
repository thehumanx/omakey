package dev.omakey.app.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.omakey.app.keyboard.ui.KeyboardRoot
import dev.omakey.core.clipboard.ClipboardHistoryStore
import dev.omakey.core.clipboard.ClipboardPreferences
import dev.omakey.core.db.ClipboardEntity
import dev.omakey.core.db.WordEntity
import dev.omakey.core.db.OmakeyDatabase
import dev.omakey.core.emoji.EmojiRecentsPreferences
import dev.omakey.core.emoji.EmojiSkinTonePreferences
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.input.TextEditor
import dev.omakey.core.layout.KeyboardPlacement
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.DeferredPredictionEngine
import dev.omakey.core.predict.IncognitoPreferences
import dev.omakey.core.predict.NgramPredictionEngine
import dev.omakey.core.predict.PersonalLanguageModel
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.predict.lm.LanguageModel
import dev.omakey.core.theme.AccessibilityPreferences
import dev.omakey.core.theme.FontPreferences
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.core.theme.ThemeRepository
import dev.omakey.extapi.ClipboardItem
import dev.omakey.extapi.ClipboardRepository
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.TextEditorFacade
import dev.omakey.ext.ClipboardHistoryExtension
import dev.omakey.ext.EmojiPanelExtension
import dev.omakey.ext.LazyExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * IME entry point. onCreate builds long-lived singletons only (database, prediction engine,
 * extension registry) — layout/gesture/view objects are deferred to onCreateInputView so the
 * service's resident memory stays minimal until a keyboard view actually exists.
 */
class OmakeyInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var database: OmakeyDatabase
    private val predictionEngine = DeferredPredictionEngine()
    private lateinit var personalModel: PersonalLanguageModel
    private lateinit var incognitoPreferences: IncognitoPreferences
    private lateinit var autocorrectIndex: AutocorrectIndex
    private lateinit var autocorrectPreferences: AutocorrectPreferences
    private lateinit var predictionPreferences: PredictionPreferences
    private lateinit var extensionRegistry: LazyExtensionRegistry
    private lateinit var textEditor: TextEditor
    private lateinit var themeRepository: ThemeRepository
    private lateinit var accessibilityPreferences: AccessibilityPreferences
    private lateinit var layoutPreferences: LayoutPreferences
    private lateinit var fontPreferences: FontPreferences
    private lateinit var gesturePreferences: GesturePreferences
    private lateinit var topStripTabPreferences: TopStripTabPreferences
    private lateinit var hapticSoundPreferences: HapticSoundPreferences
    private lateinit var emojiRecentsPreferences: EmojiRecentsPreferences
    private lateinit var emojiSkinTonePreferences: EmojiSkinTonePreferences
    private lateinit var keyboardFeedback: KeyboardFeedback
    private var keyboardViewModel: KeyboardViewModel? = null

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var clipboardPreferences: ClipboardPreferences
    private lateinit var clipboardHistory: ClipboardHistoryStore
    private var lastCapturedClipText: String? = null
    private var lastCapturedClipUri: String? = null
    // Set right before omakey's own Copy/Cut buttons trigger a system clipboard change (see
    // onClipboardCopy below). Reading ClipboardManager.primaryClip is what triggers Android 12+'s
    // "app read your clipboard" toast — skipping that read entirely for a change omakey itself
    // just caused (the text is already known, no read needed) is what avoids firing a second,
    // redundant toast on top of the OS's own unavoidable "Copied to clipboard" one.
    private var suppressNextClipboardRead = false
    // Real bug, fixed: this used to insert the raw, untrimmed `selectedText()` (e.g. "Select all"
    // on a multi-line field very often selects a trailing newline/whitespace the user never
    // meant to copy), while captureCurrentClipboardIfNew() below trims before comparing/inserting.
    // If suppressNextClipboardRead ever failed to suppress the listener for this same copy (e.g.
    // ClipboardManager delivering more than one change callback for a single setPrimaryClip, a
    // known platform quirk on some OEM builds) the two paths' `lastCapturedClipText` values
    // wouldn't match — trimmed vs untrimmed — so the fallback path's own dedupe check silently
    // failed too, and the same copy landed in the DB twice. Trimming both the same way closes
    // that gap: even if the listener double-fires, the fallback path's dedupe now actually dedupes.
    private val onClipboardCopy: (String) -> Unit = { rawText ->
        val text = rawText.trim()
        if (text.isNotEmpty()) {
            // Set even when the copy won't be recorded: suppression exists to stop the listener
            // re-reading primaryClip for a change omakey itself caused (which fires the OS's "read
            // your clipboard" toast). That read is pointless whether or not we go on to store the
            // text, and skipping the flag while incognito would trade a privacy fix for a spurious
            // toast every time the user copies out of a password field.
            suppressNextClipboardRead = true
            lastCapturedClipText = text
            lastCapturedClipUri = null
            if (clipboardPreferences.settings.value.historyEnabled && !incognitoPreferences.incognito.value) {
                serviceScope.launch {
                    database.clipboardDao()
                        .insert(ClipboardEntity(content = text, timestamp = System.currentTimeMillis()))
                    clipboardHistory.trim()
                }
            }
        }
    }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (suppressNextClipboardRead) {
            suppressNextClipboardRead = false
        } else {
            serviceScope.launch { captureCurrentClipboardIfNew() }
        }
    }

    /**
     * The clipboard's current text, for [KeyboardViewModel]'s Paste button — which commits it
     * itself instead of delegating to the host app, so that the paste can be recorded as a single
     * undo step. Null for an image clip, an empty clip, or a clipboard that can't be read.
     *
     * Unlike [captureCurrentClipboardIfNew], this runs only in direct response to the user tapping
     * Paste on omakey's own toolbar, so omakey is unambiguously the focused IME — which
     * `ClipboardService.showAccessNotificationLocked` exempts from the "pasted from your clipboard"
     * toast. Deliberately *not* trimmed: unlike clipboard-history capture, a paste must reproduce
     * the clip exactly, and the recorded undo step has to match what was actually inserted.
     */
    private fun currentClipboardText(): CharSequence? {
        val clip = clipboardManager.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        if (clip.description?.hasMimeType("image/*") == true) return null
        return clip.getItemAt(0).coerceToText(this)?.takeIf { it.isNotEmpty() }
    }

    /** Shared by [clipboardListener] (fires on every clipboard *change* while the keyboard is on
     * screen) and `ClipboardRepository.captureCurrentClipboard` below (a one-shot catch-up read —
     * see its call site's doc for why that's needed at all). Both just want "look at whatever's
     * on the clipboard right now and capture it if it's new"; the only difference is *when* and
     * *from what coroutine* they're called — this is `suspend` (does the image-copy/DB-insert
     * work directly, no nested `launch`) specifically so the panel-open caller can `await` it
     * finishing before reloading its own list, instead of racing a detached coroutine. */
    private suspend fun captureCurrentClipboardIfNew() {
        val clip = clipboardManager.primaryClip?.takeIf { it.itemCount > 0 } ?: return
        if (!shouldCaptureClip(clip.description)) return
        val item = clip.getItemAt(0)
        val imageUri = item.uri?.takeIf { clip.description?.hasMimeType("image/*") == true }
        if (imageUri != null) {
            if (imageUri.toString() == lastCapturedClipUri) return
            lastCapturedClipUri = imageUri.toString()
            lastCapturedClipText = null
            // The clip's content:// URI grant is only guaranteed valid for the moment of capture,
            // not whenever the user later opens the clipboard panel — so the bytes are copied
            // into app-private storage right now, not just the URI referenced.
            val path = copyClipboardImage(imageUri) ?: return
            database.clipboardDao().insert(
                ClipboardEntity(
                    content = "Image",
                    timestamp = System.currentTimeMillis(),
                    contentType = ClipboardEntity.TYPE_IMAGE,
                    imagePath = path,
                ),
            )
            clipboardHistory.trim()
            return
        }
        val text = item.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty() || text == lastCapturedClipText) return
        lastCapturedClipText = text
        lastCapturedClipUri = null
        database.clipboardDao().insert(ClipboardEntity(content = text, timestamp = System.currentTimeMillis()))
        clipboardHistory.trim()
    }

    /**
     * Whether a clip may be written to clipboard history at all. History is a convenience; a
     * plaintext on-device copy of a secret is not a tradeoff a keyboard gets to make on the user's
     * behalf, so both gates here fail closed.
     *
     * Two independent reasons to refuse:
     *
     *  - **The clip is marked sensitive.** Password managers and any app that has thought about it
     *    set `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) precisely so that keyboards and
     *    clipboard managers don't retain the value. It is a compile-time String constant, so
     *    naming it here is safe on older releases — the extra simply won't be present.
     *  - **Incognito is engaged.** Either the user asked for it, or the focused field is a password
     *    / no-personalised-learning field (`KeyboardViewModel.resetForNewField`).
     *
     * The second gate closes an asymmetry that was live until now and that nobody had decided on:
     * `isSensitiveField()` kept typed passwords out of the *dictionary*, but the clipboard listener
     * ran ungated for the whole time the keyboard was on screen. Typing a password was protected;
     * copying one was not, and it landed in plaintext SQLite retained ~50 entries deep.
     */
    // InlinedApi: EXTRA_IS_SENSITIVE is a `static final String`, so the compiler inlines its value
    // and nothing looks the field up at runtime. On pre-33 devices the lookup simply misses and the
    // clip is treated as non-sensitive — which is the only thing it could be, since no pre-33 app
    // can set the extra in the first place.
    @Suppress("InlinedApi")
    private fun shouldCaptureClip(description: android.content.ClipDescription?): Boolean {
        if (!clipboardPreferences.settings.value.historyEnabled) return false
        if (incognitoPreferences.incognito.value) return false
        val sensitive = description?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        return !sensitive
    }

    private fun copyClipboardImage(uri: android.net.Uri): String? =
        contentResolver.openInputStream(uri)?.let { clipboardHistory.saveImage(it) }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        database = OmakeyDatabase.getInstance(applicationContext)
        personalModel = PersonalLanguageModel()
        incognitoPreferences = IncognitoPreferences(applicationContext)
        autocorrectIndex = AutocorrectIndex()
        autocorrectPreferences = AutocorrectPreferences(applicationContext)
        predictionPreferences = PredictionPreferences(applicationContext)
        textEditor = TextEditor { currentInputConnection }
        themeRepository = ThemeRepository(applicationContext)
        accessibilityPreferences = AccessibilityPreferences(applicationContext)
        layoutPreferences = LayoutPreferences(applicationContext)
        fontPreferences = FontPreferences(applicationContext)
        gesturePreferences = GesturePreferences(applicationContext)
        topStripTabPreferences = TopStripTabPreferences(applicationContext)
        hapticSoundPreferences = HapticSoundPreferences(applicationContext)
        emojiRecentsPreferences = EmojiRecentsPreferences(applicationContext)
        emojiSkinTonePreferences = EmojiSkinTonePreferences(applicationContext)
        keyboardFeedback = VibratorKeyboardFeedback(applicationContext, hapticSoundPreferences)

        // Idempotent (WorkManager's own ExistingPeriodicWorkPolicy.KEEP) — safe to call on every
        // keyboard process start rather than needing its own "already scheduled" bookkeeping. This
        // is what keeps the 12h check running across reboots without a dedicated boot receiver:
        // the IME process restarts the moment the keyboard is used again.
        // Closed immediately: this instance exists only to answer one question at startup, and
        // nothing else in the service holds it. Left open it would sit registered as a change
        // listener on a preference it will never read again.
        val updatePreferences = dev.omakey.core.update.UpdatePreferences(applicationContext)
        if (updatePreferences.settings.value.autoCheckEnabled) {
            dev.omakey.app.update.UpdateWorkScheduler.schedule(applicationContext)
        }
        updatePreferences.close()

        // Off the main thread so onCreateInputView is never blocked — the keyboard is typeable
        // immediately and suggestions populate the moment this finishes, which is fast now: the
        // model is memory-mapped rather than parsed, so this is a few page faults plus loading
        // however many words the user has personally saved.
        //
        // This replaces a first-run import that inserted ~180,000 rows into SQLite in batches and
        // needed resumability machinery because the OS could kill the service partway through.
        // There is nothing left to resume: mapping a file either succeeds or throws.
        serviceScope.launch {
            runCatching {
                val model = LanguageModel.load(applicationContext, KeyboardLocale.Default.languageModelAsset)
                personalModel.load(
                    database.wordDao().allUserAdded().map {
                        PersonalLanguageModel.Entry(
                            word = it.word,
                            count = it.frequency / WordEntity.COUNT_SCALE,
                            lastUsed = it.lastUsedTimestamp,
                            explicit = it.explicit,
                        )
                    },
                    model,
                )
                autocorrectIndex.load(model, personalModel)
                predictionEngine.delegate = NgramPredictionEngine(model, database.wordDao(), personalModel)
            }.onFailure { Log.e(TAG, "Language model unavailable; typing works, suggestions won't", it) }
            // The old importer tracked its progress here. Left-over state is meaningless now and
            // would otherwise sit in the app's data directory forever.
            applicationContext.getSharedPreferences("omakey_seed_state", Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        extensionRegistry = LazyExtensionRegistry(contextProvider = ::buildExtensionContext)
        extensionRegistry.registerFactory(ClipboardHistoryExtension().id) { ClipboardHistoryExtension() }
        extensionRegistry.registerFactory(EmojiPanelExtension().id) { EmojiPanelExtension() }

        // NOT registered here — see onStartInputView()/onFinishInputView() below. Registering for
        // the whole service lifetime meant this listener (and the Android 12+ "app read your
        // clipboard" toast it triggers) fired every time *any* app on the device copied anything,
        // even while omakey wasn't visible — surprising and needlessly clipboard-hungry for a
        // keyboard that isn't currently in front of the user.
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardPreferences = ClipboardPreferences(applicationContext)
        clipboardHistory = ClipboardHistoryStore(applicationContext, database.clipboardDao())
    }

    private fun buildExtensionContext(): ExtensionContext = object : ExtensionContext {
        // Read per call, not captured: the user can change the tone in Settings while the panel is
        // open, and EmojiSkinTonePreferences' own listener keeps this instance's flow current.
        override fun withSkinTone(emoji: String): String = emojiSkinTonePreferences.skinTone.value.apply(emoji)

        override val textEditor: TextEditorFacade = object : TextEditorFacade {
            // One batched commitText rather than a loop over Chars — a loop splits every emoji's
            // surrogate pair across two calls, and every skin-tone modifier off the emoji it
            // modifies.
            override fun insertText(text: String) = this@OmakeyInputMethodService.textEditor.insertText(text)
            override fun deleteBackward(count: Int) {
                repeat(count) { this@OmakeyInputMethodService.textEditor.deleteCharacterBackward() }
                // Deleting via an extension (e.g. the emoji panel's own backspace) bypasses
                // KeyboardViewModel.onDeleteCharacter() entirely — nothing else refreshes the
                // suggestion strip for this path, so it kept showing whatever was suggested
                // before the delete, stale, real bug fixed.
                keyboardViewModel?.refreshSuggestionsAfterDeletion()
            }
        }
        override val clipboardRepository: ClipboardRepository = object : ClipboardRepository {
            override suspend fun recent(limit: Int): List<ClipboardItem> =
                database.clipboardDao().recent(limit).map {
                    ClipboardItem(
                        id = it.id,
                        content = it.content,
                        timestamp = it.timestamp,
                        pinned = it.pinned,
                        contentType = if (it.contentType == ClipboardEntity.TYPE_IMAGE) {
                            dev.omakey.extapi.ClipboardContentType.IMAGE
                        } else {
                            dev.omakey.extapi.ClipboardContentType.TEXT
                        },
                        imagePath = it.imagePath,
                    )
                }
            override suspend fun pin(id: Long, pinned: Boolean) {
                database.clipboardDao().setPinned(id, pinned)
            }
            override suspend fun delete(id: Long) = clipboardHistory.delete(id)
            override suspend fun captureCurrentClipboard() = captureCurrentClipboardIfNew()
        }
        override val emojiRecents: dev.omakey.extapi.EmojiRecentsRepository = object : dev.omakey.extapi.EmojiRecentsRepository {
            override fun recent(): List<String> = emojiRecentsPreferences.recents.value
            override fun recordUse(emoji: String) = emojiRecentsPreferences.recordUse(emoji)
        }
        override fun requestPanelClose() {
            keyboardViewModel?.extensionHost?.close()
        }
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

        // Compose's WindowRecomposer resolves tree owners from the window's root DecorView, not
        // from the ComposeView itself, so the owners must be attached there too — InputMethodService
        // hosts its content in a separate Dialog-backed window, which has no owners by default.
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        val viewModel = KeyboardViewModel(
            textEditor = textEditor,
            predictionEngine = predictionEngine,
            predictionReady = predictionEngine.ready,
            autocorrectIndex = autocorrectIndex,
            autocorrectPreferences = autocorrectPreferences,
            predictionPreferences = predictionPreferences,
            incognitoPreferences = incognitoPreferences,
            extensionRegistry = extensionRegistry,
            themeRepository = themeRepository,
            layoutPreferences = layoutPreferences,
            fontPreferences = fontPreferences,
            gesturePreferences = gesturePreferences,
            topStripTabPreferences = topStripTabPreferences,
            scope = serviceScope,
            onClipboardCopy = onClipboardCopy,
            clipboardText = ::currentClipboardText,
            emojiSkinTone = { emojiSkinTonePreferences.skinTone.value },
        )
        keyboardViewModel = viewModel

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val uiState by viewModel.uiState.collectAsState()
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalOmakeyTheme provides resolveEffectiveTheme(uiState.theme, uiState.useSystemAccent),
                    dev.omakey.core.theme.LocalKeyboardLayoutMode provides uiState.layoutMode,
                ) {
                    KeyboardRoot(
                        viewModel,
                        accessibilityPreferences,
                        onOpenSettings = ::openSettings,
                        feedback = keyboardFeedback,
                        onKeyboardBoundsChanged = ::onKeyboardBoundsChanged,
                    )
                }
            }
        }
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        keyboardViewModel?.resetForNewField(info)
        // Only listens for clipboard changes while the keyboard is actually on screen — see the
        // comment in onCreate() for why this isn't registered for the whole service lifetime.
        // onStartInputView can fire again without a matching onFinishInputView in between (e.g.
        // switching fields within the same app calls this again with restarting = true) —
        // removing any previous registration first keeps exactly one active at a time. Without
        // this, a duplicate registration meant every clipboard change fired the listener twice:
        // the first invocation consumed the copy-suppression flag below and skipped the read,
        // but the second (extra, un-removed) registration didn't see the flag anymore and read
        // primaryClip for real — silently firing the "read your clipboard" toast a second time.
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
    }

    // Fires for every selection/cursor change regardless of cause — our own commits, a tap
    // elsewhere in the text, arrow-key navigation, autofill. KeyboardViewModel.onCursorMoved()
    // cheaply no-ops for the ordinary "cursor is right where our own typing left it" case, so
    // this doesn't need to filter out our own edits before forwarding.
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        keyboardViewModel?.onCursorMoved()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Where the keyboard itself is, in window coordinates — reported by `KeyboardRoot` on every
     * layout pass. Only consulted while floating.
     *
     * A plain field, read by [onComputeInsets] whenever the framework next recomputes insets rather
     * than pushed at it. There is no public "insets are stale, recompute now" call; the framework
     * recomputes on its own layout/draw pass, which a Compose layout change triggers anyway. The
     * cost is that a drag can be one frame ahead of the touchable region — invisible in practice,
     * and far better than trying to force a recomputation from inside a layout pass, which is how
     * layout loops start.
     */
    @Volatile private var keyboardBounds: android.graphics.Rect? = null

    private fun onKeyboardBoundsChanged(bounds: androidx.compose.ui.geometry.Rect) {
        val left = bounds.left.toInt()
        val top = bounds.top.toInt()
        val right = bounds.right.toInt()
        val bottom = bounds.bottom.toInt()
        // Called from onGloballyPositioned, so it fires on every layout pass — which during a drag
        // is every frame. Comparing before allocating keeps a moved keyboard from producing a new
        // Rect sixty times a second for the garbage collector, and makes the common case (a layout
        // pass that didn't move anything) free.
        val current = keyboardBounds
        if (current != null && current.left == left && current.top == top &&
            current.right == right && current.bottom == bottom
        ) {
            return
        }
        keyboardBounds = android.graphics.Rect(left, top, right, bottom)
    }

    /**
     * Makes floating mode possible, and does nothing in any other placement.
     *
     * Two separate things have to be arranged, and they are easy to confuse:
     *
     *  - **The app must not be pushed up.** `contentTopInsets`/`visibleTopInsets` are what the host
     *    app resizes around. Setting both to the input view's full height says "the IME occupies no
     *    space at the bottom", so the app lays out as if no keyboard were showing — which is the
     *    point of floating, since the keyboard may be nowhere near the bottom.
     *  - **Touches outside the keyboard must reach the app.** By default the whole IME window
     *    swallows them, which for a floating keyboard means a large invisible dead zone.
     *    `TOUCHABLE_INSETS_REGION` plus a `touchableRegion` of exactly the keyboard's own rectangle
     *    limits us to what we actually draw.
     *
     * Falls back to the default behaviour whenever bounds haven't been reported yet — a floating
     * keyboard that is briefly fully touchable is recoverable; one that is briefly *untouchable* is
     * a keyboard the user cannot type on.
     *
     * Known limitation: a host app that ignores `contentTopInsets` will still resize around us.
     * There is nothing an IME can do about that, and every floating keyboard has the same gap.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (layoutPreferences.settings.value.placement != KeyboardPlacement.FLOATING) return
        val bounds = keyboardBounds ?: return
        val viewHeight = window?.window?.decorView?.height ?: return
        outInsets.contentTopInsets = viewHeight
        outInsets.visibleTopInsets = viewHeight
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(bounds)
    }

    private fun openSettings() {
        val intent = android.content.Intent(this, dev.omakey.app.settings.SettingsActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        // Symmetry with the registrations in onCreate. Not strictly required — SharedPreferences
        // holds listeners weakly, which is why nothing broke while nothing anywhere unregistered —
        // but "we rely on an implementation detail nobody chose to rely on" is a worse position
        // than releasing what we took, and the service has a definite end of life to do it at.
        incognitoPreferences.close()
        clipboardPreferences.close()
        autocorrectPreferences.close()
        predictionPreferences.close()
        themeRepository.close()
        accessibilityPreferences.close()
        layoutPreferences.close()
        fontPreferences.close()
        gesturePreferences.close()
        topStripTabPreferences.close()
        hapticSoundPreferences.close()
        emojiRecentsPreferences.close()
        emojiSkinTonePreferences.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OmakeyIME"

        /** Ceiling on a single copied clipboard image. Generous enough for any screenshot or photo
         * a user would plausibly paste, small enough that 50 of them is a bounded amount of
         * app-private storage rather than an open-ended one. */
        const val MAX_CLIPBOARD_IMAGE_BYTES = 8L * 1024 * 1024
    }
}
