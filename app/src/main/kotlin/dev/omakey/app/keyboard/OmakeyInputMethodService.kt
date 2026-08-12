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
    private lateinit var hapticSoundPreferences: HapticSoundPreferences
    private lateinit var keyboardFeedback: KeyboardFeedback
    private var keyboardViewModel: KeyboardViewModel? = null

    private lateinit var clipboardManager: ClipboardManager
    private var lastCapturedClipText: String? = null
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = clipboardManager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty() || text == lastCapturedClipText) return@OnPrimaryClipChangedListener
        lastCapturedClipText = text
        serviceScope.launch {
            database.clipboardDao().insert(
                ClipboardEntity(content = text, timestamp = System.currentTimeMillis()),
            )
            database.clipboardDao().trimUnpinned()
        }
    }

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
        hapticSoundPreferences = HapticSoundPreferences(applicationContext)
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

        // Registered for the service's lifetime, not just while the panel is open — foreground-IME
        // clipboard reads are permitted without a runtime permission on modern Android (the
        // Android 12+ "clipboard read" toast is expected here, not a bug).
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    private fun buildExtensionContext(): ExtensionContext = object : ExtensionContext {
        override val textEditor: TextEditorFacade = object : TextEditorFacade {
            override fun insertText(text: String) = text.forEach { this@OmakeyInputMethodService.textEditor.commitCharacter(it) }
            override fun deleteBackward(count: Int) = repeat(count) { this@OmakeyInputMethodService.textEditor.deleteCharacterBackward() }
        }
        override val clipboardRepository: ClipboardRepository = object : ClipboardRepository {
            override suspend fun recent(limit: Int): List<ClipboardItem> =
                database.clipboardDao().recent(limit).map { ClipboardItem(it.id, it.content, it.timestamp, it.pinned) }
            override suspend fun pin(id: Long, pinned: Boolean) = Unit // v1: pin toggling deferred
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
            scope = serviceScope,
        )
        keyboardViewModel = viewModel

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val uiState by viewModel.uiState.collectAsState()
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalOmakeyTheme provides uiState.theme,
                ) {
                    KeyboardRoot(
                        viewModel,
                        accessibilityPreferences,
                        onHideKeyboard = { requestHideSelf(0) },
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
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
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
