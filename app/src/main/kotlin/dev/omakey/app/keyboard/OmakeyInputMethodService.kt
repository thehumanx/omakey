package dev.omakey.app.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
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
import dev.omakey.core.db.ClipboardEntity
import dev.omakey.core.db.OmakeyDatabase
import dev.omakey.core.emoji.EmojiRecentsPreferences
import dev.omakey.core.feedback.HapticSoundPreferences
import dev.omakey.core.gesture.GesturePreferences
import dev.omakey.core.input.TextEditor
import dev.omakey.core.layout.LayoutPreferences
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.AutocorrectPreferences
import dev.omakey.core.predict.PredictionPreferences
import dev.omakey.core.predict.DictionarySeeder
import dev.omakey.core.predict.FrequencyNgramPredictionEngine
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
    private lateinit var predictionEngine: FrequencyNgramPredictionEngine
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
    private lateinit var keyboardFeedback: KeyboardFeedback
    private var keyboardViewModel: KeyboardViewModel? = null

    private lateinit var clipboardManager: ClipboardManager
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
            suppressNextClipboardRead = true
            lastCapturedClipText = text
            lastCapturedClipUri = null
            serviceScope.launch {
                database.clipboardDao().insert(ClipboardEntity(content = text, timestamp = System.currentTimeMillis()))
                database.clipboardDao().trimUnpinned()
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

    /** Shared by [clipboardListener] (fires on every clipboard *change* while the keyboard is on
     * screen) and `ClipboardRepository.captureCurrentClipboard` below (a one-shot catch-up read —
     * see its call site's doc for why that's needed at all). Both just want "look at whatever's
     * on the clipboard right now and capture it if it's new"; the only difference is *when* and
     * *from what coroutine* they're called — this is `suspend` (does the image-copy/DB-insert
     * work directly, no nested `launch`) specifically so the panel-open caller can `await` it
     * finishing before reloading its own list, instead of racing a detached coroutine. */
    private suspend fun captureCurrentClipboardIfNew() {
        val clip = clipboardManager.primaryClip?.takeIf { it.itemCount > 0 } ?: return
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
            database.clipboardDao().trimUnpinned()
            return
        }
        val text = item.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty() || text == lastCapturedClipText) return
        lastCapturedClipText = text
        lastCapturedClipUri = null
        database.clipboardDao().insert(ClipboardEntity(content = text, timestamp = System.currentTimeMillis()))
        database.clipboardDao().trimUnpinned()
    }

    private fun copyClipboardImage(uri: android.net.Uri): String? = runCatching {
        val dir = java.io.File(filesDir, "clipboard_images").apply { mkdirs() }
        val file = java.io.File(dir, "clip_${System.currentTimeMillis()}.png")
        val input = contentResolver.openInputStream(uri) ?: return null
        input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
        file.absolutePath
    }.getOrNull()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        database = OmakeyDatabase.getInstance(applicationContext)
        predictionEngine = FrequencyNgramPredictionEngine(database.wordDao(), database.bigramDao())
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
        keyboardFeedback = VibratorKeyboardFeedback(applicationContext, hapticSoundPreferences)

        // Async, off the main thread, and a no-op after the first run — must not block
        // onCreateInputView; the keyboard is typeable immediately and suggestions populate once
        // this finishes.
        serviceScope.launch {
            val seeder = DictionarySeeder(database.wordDao(), database.bigramDao())
            seeder.seedIfNeeded(applicationContext)
            seeder.seedBigramsIfNeeded(applicationContext)
            // Loaded after seeding so a fresh install's very first autocorrect check already has
            // the full dictionary, not just whatever existed before this coroutine ran.
            autocorrectIndex.load(database.wordDao().all())
        }

        extensionRegistry = LazyExtensionRegistry(contextProvider = ::buildExtensionContext)
        extensionRegistry.registerFactory(ClipboardHistoryExtension().id) { ClipboardHistoryExtension() }
        extensionRegistry.registerFactory(EmojiPanelExtension().id) { EmojiPanelExtension() }
        // GifSearchExtension is a stub — a real implementation needs INTERNET, which the app
        // deliberately doesn't request. Hidden from the panel tab strip until that's built for real.

        // NOT registered here — see onStartInputView()/onFinishInputView() below. Registering for
        // the whole service lifetime meant this listener (and the Android 12+ "app read your
        // clipboard" toast it triggers) fired every time *any* app on the device copied anything,
        // even while omakey wasn't visible — surprising and needlessly clipboard-hungry for a
        // keyboard that isn't currently in front of the user.
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private fun buildExtensionContext(): ExtensionContext = object : ExtensionContext {
        override val textEditor: TextEditorFacade = object : TextEditorFacade {
            override fun insertText(text: String) = text.forEach { this@OmakeyInputMethodService.textEditor.commitCharacter(it) }
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
            override suspend fun pin(id: Long, pinned: Boolean) = Unit // v1: pin toggling deferred
            override suspend fun delete(id: Long) {
                // Delete the backing image file too, if any — otherwise removing a clipboard row
                // would leave an orphaned file in app-private storage indefinitely.
                database.clipboardDao().findById(id)?.imagePath?.let { path ->
                    runCatching { java.io.File(path).delete() }
                }
                database.clipboardDao().delete(id)
            }
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
            autocorrectIndex = autocorrectIndex,
            autocorrectPreferences = autocorrectPreferences,
            predictionPreferences = predictionPreferences,
            extensionRegistry = extensionRegistry,
            themeRepository = themeRepository,
            layoutPreferences = layoutPreferences,
            fontPreferences = fontPreferences,
            gesturePreferences = gesturePreferences,
            topStripTabPreferences = topStripTabPreferences,
            scope = serviceScope,
            onClipboardCopy = onClipboardCopy,
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

    private fun openSettings() {
        val intent = android.content.Intent(this, dev.omakey.app.settings.SettingsActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        serviceScope.cancel()
        super.onDestroy()
    }
}
