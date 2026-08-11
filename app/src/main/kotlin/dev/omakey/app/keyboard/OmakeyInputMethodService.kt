package dev.omakey.app.keyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
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
import dev.omakey.core.db.OmakeyDatabase
import dev.omakey.core.input.TextEditor
import dev.omakey.core.predict.FrequencyNgramPredictionEngine
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.extapi.ClipboardItem
import dev.omakey.extapi.ClipboardRepository
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.TextEditorFacade
import dev.omakey.ext.ClipboardHistoryExtension
import dev.omakey.ext.EmojiPanelExtension
import dev.omakey.ext.GifSearchExtension
import dev.omakey.ext.LazyExtensionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private lateinit var extensionRegistry: LazyExtensionRegistry
    private lateinit var textEditor: TextEditor
    private var keyboardViewModel: KeyboardViewModel? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        database = OmakeyDatabase.getInstance(applicationContext)
        predictionEngine = FrequencyNgramPredictionEngine(database.wordDao(), database.bigramDao())
        textEditor = TextEditor { currentInputConnection }

        extensionRegistry = LazyExtensionRegistry(contextProvider = ::buildExtensionContext)
        extensionRegistry.registerFactory(ClipboardHistoryExtension().id) { ClipboardHistoryExtension() }
        extensionRegistry.registerFactory(EmojiPanelExtension().id) { EmojiPanelExtension() }
        extensionRegistry.registerFactory(GifSearchExtension().id) { GifSearchExtension() }
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
            extensionRegistry = extensionRegistry,
            scope = serviceScope,
        )
        keyboardViewModel = viewModel

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalOmakeyTheme provides viewModel.uiState.value.theme,
                ) {
                    KeyboardRoot(viewModel)
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
        keyboardViewModel?.resetForNewField()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        super.onDestroy()
    }
}
