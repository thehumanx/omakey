package dev.omakey.app.settings

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.omakey.core.db.OmakeyDatabase
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import dev.omakey.app.design.ActionSheet
import dev.omakey.app.design.ButtonKind
import dev.omakey.app.design.ConfirmSheet
import dev.omakey.app.design.DragReorderList
import dev.omakey.app.design.EmptyState
import dev.omakey.app.design.IconAction
import dev.omakey.app.design.Omakey
import dev.omakey.app.design.OmakeyButton
import dev.omakey.app.design.OmakeyField
import dev.omakey.app.design.PageHeader
import dev.omakey.app.design.SettingsCard
import dev.omakey.app.design.SheetAction
import dev.omakey.core.icons.OmakeyIcons
import dev.omakey.core.snippets.KeystoreSecretCipher
import dev.omakey.core.snippets.SnippetRepository
import dev.omakey.core.snippets.SnippetRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add, edit, reorder and delete snippets. **Not exported** (see the manifest): only omakey itself
 * can open it, so no other app can send an Intent that opens a secret's form. The keyboard opens
 * it straight on one snippet ([EXTRA_SNIPPET_ID], or [NEW_SNIPPET] to add); Settings opens the
 * list. While a secret's form is open the window is FLAG_SECURE: no screenshots, no recents
 * thumbnail, no screen recording of the password.
 */
/**
 * What's typed in the form's text field, kept across rotation without going into the saved-state
 * Bundle (which is plain and can be written to disk). Rotating used to bring back the stored
 * password over a new one being typed, and a former password could land in the Bundle.
 * Lost only if Android kills the process, when the form reloads from storage.
 */
class SnippetFormMemory : androidx.lifecycle.ViewModel() {
    /** Which form the text belongs to: a snippet's id, or NEW_SNIPPET. Null: none open. */
    var formKey: Long? = null
    val text = mutableStateOf("")
}

class SnippetsActivity : ComponentActivity() {

    private val formMemory: SnippetFormMemory by lazy { androidx.lifecycle.ViewModelProvider(this)[SnippetFormMemory::class.java] }

    companion object {
        const val EXTRA_SNIPPET_ID = "snippet_id"
        const val NEW_SNIPPET = -1L
        /** Starts the new-snippet form with this text (clipboard "Save as snippet"). */
        const val EXTRA_PREFILL_TEXT = "prefill_text"
    }

    /** Leaving goes back out to the right, the way the screen came in. */
    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, dev.omakey.app.R.anim.page_in_left, dev.omakey.app.R.anim.page_out_right)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(dev.omakey.app.R.anim.page_in_left, dev.omakey.app.R.anim.page_out_right)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = OmakeyDatabase.getInstance(applicationContext)
        val repository = SnippetRepository(
            database.snippetDao(),
            KeystoreSecretCipher(),
            wipeFreedSpace = { withContext(Dispatchers.IO) { runCatching { database.checkpointAndTruncate() } } },
        )
        val startedOn: Long? = if (intent.hasExtra(EXTRA_SNIPPET_ID)) intent.getLongExtra(EXTRA_SNIPPET_ID, NEW_SNIPPET) else null
        val prefill = intent.getStringExtra(EXTRA_PREFILL_TEXT)?.take(SnippetRules.MAX_TEXT)
        setContent {
            OmakeySettingsTheme {
                Surface(Modifier.fillMaxSize(), color = Omakey.colors.ground) {
                    // null: the list; NEW_SNIPPET: the add form; anything else: that snippet's form.
                    // Saveable, so rotating keeps you on the form you were on.
                    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startedOn) }
                    // List ↔ form: the form comes in from the right and goes back out to the right.
                    androidx.compose.animation.AnimatedContent(
                        targetState = editing,
                        transitionSpec = {
                            if (targetState != null) dev.omakey.app.design.Motion.push() else dev.omakey.app.design.Motion.pop()
                        },
                        label = "snippets-screen",
                    ) { current ->
                    if (current == null) {
                        SnippetList(repository, onEdit = { editing = it }, onClose = ::finish)
                    } else {
                        val close = { if (startedOn != null) finish() else editing = null }
                        BackHandler(onBack = close)
                        SnippetForm(
                            repository = repository,
                            memory = formMemory,
                            isChangingConfigurations = { isChangingConfigurations },
                            id = current.takeIf { it != NEW_SNIPPET },
                            prefill = prefill.takeIf { current == NEW_SNIPPET && startedOn == NEW_SNIPPET },
                            onSecure = { secure ->
                                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            },
                            onDone = close,
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun SnippetList(repository: SnippetRepository, onEdit: (Long) -> Unit, onClose: () -> Unit) {
    val snippets by repository.snippets.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var transferMessage by remember { mutableStateOf<String?>(null) }

    // Export: plain snippets only, through the system file picker. Secrets never leave the phone.
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val all = repository.snippets.first()
                    val plain = all.filter { !it.secret }.map { it.copy(text = repository.textToType(it.id)) }
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                        out.write(dev.omakey.core.snippets.SnippetTransfer.encode(plain).toByteArray(Charsets.UTF_8))
                    }
                    plain.size to all.count { it.secret }
                }.getOrNull()
            }
            transferMessage = when {
                result == null -> "Couldn't write the file."
                result.second == 0 -> "Exported ${result.first} snippets."
                else -> "Exported ${result.first} snippets. ${result.second} secret snippets were left out."
            }
        }
    }

    // Import: duplicates skipped, clashing short codes dropped, nothing written on a bad file.
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferMessage = withContext(Dispatchers.IO) {
                // A snippet file is small; refuse anything absurdly large rather than read it all.
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { it.readNBytesCompat(MAX_IMPORT_BYTES + 1) }
                }.getOrNull()
                val entries = bytes?.takeIf { it.size <= MAX_IMPORT_BYTES }
                    ?.let { dev.omakey.core.snippets.SnippetTransfer.decode(String(it, Charsets.UTF_8).removePrefix("\uFEFF")) }
                if (entries == null) {
                    "That isn't an omakey snippets file."
                } else {
                    val plan = dev.omakey.core.snippets.SnippetTransfer.plan(repository.snippets.first(), entries)
                    // One write: an import lands whole or not at all.
                    if (runCatching { repository.importAll(plan.toAdd) }.isFailure) return@withContext "Couldn't import. Nothing was added."
                    buildString {
                        append("Imported ${plan.toAdd.size}")
                        if (plan.skipped > 0) append(", skipped ${plan.skipped} duplicates")
                        if (plan.codesDropped > 0) append(", ${plan.codesDropped} short codes already in use")
                        if (plan.shortened > 0) append(", ${plan.shortened} cut to ${SnippetRules.MAX_TEXT} characters")
                        append(".")
                    }
                }
            }
        }
    }

    var showMore by remember { mutableStateOf(false) }
    val c = Omakey.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PageHeader(
            title = "Snippets",
            onBack = onClose,
            modifier = Modifier.padding(start = 4.dp),
            action = { IconAction(OmakeyIcons.More, "Import and export", { showMore = true }) },
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            item {
                Text(
                    "Text you type often, one tap away on the Snippets page above the keys.",
                    style = Omakey.type.body, color = c.muted,
                    modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 16.dp),
                )
            }
            transferMessage?.let { item { Text(it, style = Omakey.type.body, color = c.ink, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 12.dp)) } }
            if (snippets.isEmpty()) {
                item {
                    EmptyState(OmakeyIcons.Snippet, "Save your email, phone number, address or a password, and type it with one tap.")
                }
            } else {
                item {
                    DragReorderList(
                        items = snippets,
                        label = { it.name.ifBlank { SnippetRules.chipLabel(it) } },
                        onMove = { from, to ->
                            val s = snippets[from]
                            scope.launch { withContext(Dispatchers.IO) { repository.move(s.id, to - from) } }
                        },
                    ) { s, _ ->
                        SnippetRow(s, onClick = { onEdit(s.id) })
                    }
                }
                item {
                    Text(
                        "Hold a snippet and drag to change its place on the bar.",
                        style = Omakey.type.caption, color = c.subtle,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
        }
        OmakeyButton("Add a snippet", { onEdit(SnippetsActivity.NEW_SNIPPET) }, kind = ButtonKind.Primary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            "Passwords are never exported.",
            style = Omakey.type.caption, color = c.subtle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        )
    }

    if (showMore) {
        ActionSheet(
            title = "Snippets",
            actions = buildList {
                if (snippets.any { !it.secret }) {
                    add(SheetAction("Export to a file", OmakeyIcons.Download) {
                        showMore = false
                        exportLauncher.launch("omakey-snippets.json")
                    })
                }
                add(SheetAction("Import from a file", OmakeyIcons.Clipboard) {
                    showMore = false
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                })
            },
            onDismiss = { showMore = false },
        )
    }
}

/** One snippet in the list: its label over what it types (dots for a password, with a lock), and
 * its shortcut as a key cap. */
@Composable
private fun SnippetRow(s: dev.omakey.core.snippets.Snippet, onClick: () -> Unit) {
    val c = Omakey.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Edit", onClick = onClick).padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(OmakeyIcons.Grip, contentDescription = null, tint = c.subtle, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.secret) Icon(OmakeyIcons.Lock, contentDescription = "Password", tint = c.ink, modifier = Modifier.size(16.dp))
                Text(s.name.ifBlank { SnippetRules.chipLabel(s) }, style = Omakey.type.row, color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            val preview = if (s.secret) "••••••••" else s.text.orEmpty().lineSequence().firstOrNull().orEmpty()
            if (preview.isNotEmpty() && (s.secret || s.name.isNotBlank())) {
                Text(preview, style = Omakey.type.body, color = c.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        s.shortcut?.takeIf { it.isNotBlank() }?.let { code ->
            Text(
                code,
                style = Omakey.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier.background(c.tonal, RoundedCornerShape(dev.omakey.app.design.Radius.key)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SnippetForm(
    repository: SnippetRepository,
    memory: SnippetFormMemory,
    /** True while the screen is being rebuilt (rotation): the form's text is kept then. */
    isChangingConfigurations: () -> Boolean,
    id: Long?,
    /** Text for a new snippet, from "Save as snippet" on a clipboard item. */
    prefill: String? = null,
    onSecure: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val others by repository.snippets.collectAsState(initial = emptyList())
    // Saveable, so rotating the phone keeps what was typed. Except a password: Android's saved
    // state is a plain Bundle, so a password's text is never put in it (its saver writes nothing
    // while "This is a password" is on) and is unlocked again from storage instead.
    var loaded by remember { mutableStateOf(id == null) }
    var restoredOnce by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var shortcut by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var secret by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // The text lives in [memory], never in the Bundle (see SnippetFormMemory). A form opened
    // fresh starts from the prefill; one rebuilt by a rotation keeps what was typed.
    val formKey = id ?: SnippetsActivity.NEW_SNIPPET
    if (memory.formKey != formKey) {
        memory.formKey = formKey
        memory.text.value = prefill.orEmpty()
    }
    var text by memory.text
    // Closing the form forgets its text; a rotation (the activity is only being rebuilt) doesn't.
    androidx.compose.runtime.DisposableEffect(formKey) {
        onDispose { if (!isChangingConfigurations()) memory.formKey = null }
    }
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askMakeSecret by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A save is running: Save is disabled, so a double tap can't add the snippet twice.
    var saving by remember { mutableStateOf(false) }
    // The snippet was secret when this form opened. Its text stays screenshot-proof even after
    // Secret is switched off, until the form closes.
    var wasSecret by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        if (restoredOnce) {
            // Back after a rotation: the edits are still in memory. Only after Android killed the
            // process is the text gone; then it is read from storage again.
            if (text.isEmpty()) {
                val opened = withContext(Dispatchers.IO) { repository.textToType(id) }
                if (opened == null) error = "Can't unlock, re-enter" else text = opened
            }
            loaded = true
            return@LaunchedEffect
        }
        val existing = withContext(Dispatchers.IO) { repository.snippets.first().firstOrNull { it.id == id } }
        if (existing == null) { onDone(); return@LaunchedEffect }
        name = existing.name
        shortcut = existing.shortcut.orEmpty()
        secret = existing.secret
        wasSecret = existing.secret
        val opened = withContext(Dispatchers.IO) { repository.textToType(id) }
        if (opened == null) error = "Can't unlock, re-enter" else text = opened
        loaded = true
        restoredOnce = true
    }
    // No screenshots or recents thumbnail while a secret's form is open, and clear it on leaving.
    LaunchedEffect(secret, wasSecret) { onSecure(secret || wasSecret) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSecure(false) } }

    fun save(asSecret: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { repository.save(id, name, text, asSecret, shortcut.trim().ifEmpty { null }) }
            if (ok) onDone() else { error = "Couldn't secure this snippet. Try again."; saving = false }
        }
    }

    val c = Omakey.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PageHeader(
            title = if (id == null) "New snippet" else "Edit snippet",
            onBack = onDone,
            modifier = Modifier.padding(start = 4.dp),
            action = if (id == null) null else { { IconAction(OmakeyIcons.Trash, "Delete snippet", { confirmDelete = true }, tint = c.muted) } },
        )
        if (!loaded) return@Column
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. What gets typed. First, because it's the whole point; everything else is optional.
            val preview = if (!secret && '{' in text) {
                dev.omakey.core.snippets.SnippetPlaceholders.expand(
                    text, java.util.Date(), java.util.Locale.getDefault(), java.util.TimeZone.getDefault(),
                ) { "(what you copied)" }
            } else null
            OmakeyField(
                value = text, onValueChange = { text = it; if (error == "Can't unlock, re-enter") error = null },
                label = "What gets typed",
                placeholder = if (secret) "Your password" else "bibek@example.com",
                visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                // No autocorrect or suggestions, and a password field while secret, so another
                // keyboard doesn't learn it either (omakey never learns from its own screens).
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
                ),
                singleLine = secret,
                minLines = if (secret) 1 else 2,
                onGround = true,
                supporting = when {
                    secret -> "Typed when you tap the snippet. Never shown on the bar."
                    preview != null -> "Typed when you tap the snippet. Right now: \u201c$preview\u201d"
                    else -> "Typed when you tap the snippet."
                },
                trailing = if (!secret) null else { {
                    Text(
                        if (revealed) "Hide" else "Show",
                        style = Omakey.type.label, color = c.ink,
                        modifier = Modifier.minimumInteractiveComponentSize().clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (revealed) "Hide password" else "Show password") { revealed = !revealed },
                    )
                } },
            )
            if (!secret) {
                // Placeholders as buttons, not syntax to remember: each adds its {word} where the
                // text ends, and the line above shows what it becomes.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple("{date}", "Date", OmakeyIcons.Calendar), Triple("{time}", "Time", OmakeyIcons.Clock), Triple("{clipboard}", "Copied text", OmakeyIcons.Clipboard)).forEach { (token, label, icon) ->
                        Row(
                            Modifier
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(dev.omakey.app.design.Radius.key))
                                .background(c.surface)
                                .clickable(onClickLabel = "Add $label") { if (text.length + token.length <= SnippetRules.MAX_TEXT) text += token }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp))
                            Text(label, style = Omakey.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.ink)
                        }
                    }
                }
            }

            // 2. The button's label: optional for plain text (the text itself is shown), required
            // for a password (its text is never shown, so the button needs something to say).
            OmakeyField(
                value = name, onValueChange = { name = it },
                label = "Button label",
                placeholder = if (secret) "Bank" else "Work email",
                supporting = if (secret) "Shown on the bar, since the password never is." else "Shown on the bar instead of the text. Optional.",
                onGround = true,
            )

            // 3. Shortcut. Passwords have none: a shortcut is offered in the suggestion bar, where
            // a password must never appear.
            if (!secret) {
                OmakeyField(
                    value = shortcut, onValueChange = { shortcut = it },
                    label = "Shortcut",
                    placeholder = "@@",
                    supporting = "Type it anywhere, then tap the suggestion to swap it in.",
                    onGround = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
                )
            }

            // 4. Secret.
            SettingsCard(contentPadding = 16.dp) {
                UnindexedSettingToggle(
                    title = "This is a password",
                    description = "Encrypted, and hidden while locked",
                    checked = secret,
                    onCheckedChange = { secret = it; revealed = false },
                )
            }
            error?.let { Text(it, style = Omakey.type.body, color = c.danger, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
        OmakeyButton(
            "Save snippet",
            enabled = !saving,
            kind = ButtonKind.Primary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            onClick = {
                val problem = SnippetRules.validate(name, text, secret)
                    ?: if (secret) null else dev.omakey.core.snippets.SnippetCodes.validate(shortcut.trim(), others, id)
                when {
                    problem != null -> error = problem
                    !secret && SnippetRules.looksLikePassword(text) -> askMakeSecret = true
                    else -> save(secret)
                }
            },
        )
    }

    if (askMakeSecret) {
        ConfirmSheet(
            title = "Is this a password?",
            message = "It looks like one. As a password it's encrypted, and shown as dots.",
            confirmLabel = "Keep as a password",
            dismissLabel = "Keep as plain text",
            onConfirm = {
                askMakeSecret = false
                if (name.isBlank()) { secret = true; error = SnippetRules.validate(name, text, true) } else save(true)
            },
            onDismiss = { askMakeSecret = false },
            onDismissButton = { askMakeSecret = false; save(false) },
        )
    }
    if (confirmDelete && id != null) {
        ConfirmSheet(
            title = "Delete this snippet?",
            message = "You can't get it back.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                scope.launch { withContext(Dispatchers.IO) { repository.delete(id) }; onDone() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** An export holds at most a few hundred snippets of 2,000 characters; anything bigger isn't one. */
private const val MAX_IMPORT_BYTES = 4 * 1024 * 1024

/** Up to [limit] bytes. InputStream.readNBytes is Android 13+, and minSdk is 26. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
