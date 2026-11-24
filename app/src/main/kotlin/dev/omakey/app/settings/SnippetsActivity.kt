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
                Surface(Modifier.fillMaxSize()) {
                    // null: the list; NEW_SNIPPET: the add form; anything else: that snippet's form.
                    // Saveable, so rotating keeps you on the form you were on.
                    var editing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(startedOn) }
                    val current = editing
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

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("Back") }
            Text("Snippets", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Text you type often (your email, phone number, address or a password), saved here and typed with one " +
                "tap from the Snippets page: swipe the bar above the keys to reach it. Passwords are kept encrypted, " +
                "shown as •••• and hidden while the phone is locked.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onEdit(SnippetsActivity.NEW_SNIPPET) }) { Text("Add snippet") }
            TextButton(enabled = snippets.any { !it.secret }, onClick = { exportLauncher.launch("omakey-snippets.json") }) { Text("Export") }
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("Import") }
        }
        transferMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        }
        Spacer(Modifier.padding(4.dp))
        if (snippets.isEmpty()) {
            Text("No snippets yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(snippets, key = { _, s -> s.id }) { index, s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEdit(s.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(SnippetRules.chipLabel(s), Modifier.weight(1f), maxLines = 1)
                    TextButton(enabled = index > 0, onClick = { scope.launch { withContext(Dispatchers.IO) { repository.move(s.id, -1) } } }) { Text("↑") }
                    TextButton(enabled = index < snippets.lastIndex, onClick = { scope.launch { withContext(Dispatchers.IO) { repository.move(s.id, +1) } } }) { Text("↓") }
                }
                HorizontalDivider()
            }
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

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Text(if (id == null) "New snippet" else "Edit snippet", style = MaterialTheme.typography.titleLarge)
        }
        if (!loaded) return@Column

        // 1. What gets typed. First, because it's the whole point; everything else is optional.
        OutlinedTextField(
            value = text, onValueChange = { text = it; if (error == "Can't unlock, re-enter") error = null },
            label = { Text("What gets typed") },
            placeholder = { Text(if (secret) "Your password" else "e.g. bibek@example.com") },
            visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            // No autocorrect or suggestions, and a password field while secret, so another
            // keyboard doesn't learn it either (omakey never learns from its own screens).
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            supportingText = {
                Text(
                    if (secret) "Typed when you tap this snippet. Never shown on the button."
                    else "Typed when you tap this snippet on the bar above the keys. ${text.length} / ${SnippetRules.MAX_TEXT}",
                )
            },
        )
        if (secret) {
            TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide password" else "Show password") }
        } else {
            // Placeholders as buttons, not syntax to remember: each adds its {word} where the
            // text ends, and the preview shows what it becomes.
            Text("Fill in automatically when typed:", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("{date}" to "+ Today's date", "{time}" to "+ Time now", "{clipboard}" to "+ What you copied").forEach { (token, label) ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { if (text.length + token.length <= SnippetRules.MAX_TEXT) text += token },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            if ('{' in text) {
                val preview = dev.omakey.core.snippets.SnippetPlaceholders.expand(
                    text, java.util.Date(), java.util.Locale.getDefault(), java.util.TimeZone.getDefault(),
                ) { "(what you copied)" }
                Text(
                    "Right now this would type: $preview",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 2. Secret.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("This is a password")
                Text(
                    "Kept encrypted on this phone, shown as •••• on the bar, and hidden while the phone is locked.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = secret, onCheckedChange = { secret = it; revealed = false })
        }

        // 3. The button's label: optional for plain text (the text itself is shown), required for
        // a password (its text is never shown, so the button needs something to say).
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text(if (secret) "Button label" else "Button label (optional)") },
            placeholder = { Text(if (secret) "e.g. Bank" else "e.g. Work email") },
            supportingText = {
                Text(
                    if (secret) "What the button on the bar says, since the password itself is never shown."
                    else "What the button on the bar says. Leave empty to show the text itself.",
                )
            },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        // 4. Shortcut. Passwords have none: a shortcut is offered in the suggestion bar, where a
        // password must never appear.
        if (!secret) {
            OutlinedTextField(
                value = shortcut, onValueChange = { shortcut = it },
                label = { Text("Shortcut (optional)") },
                placeholder = { Text("e.g. @@") },
                supportingText = {
                    Text("Type this in any app and the snippet pops up in the suggestion bar; tap it to swap. Example: @@ for your email.")
                },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false),
            )
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !saving, onClick = {
                val problem = SnippetRules.validate(name, text, secret)
                    ?: if (secret) null else dev.omakey.core.snippets.SnippetCodes.validate(shortcut.trim(), others, id)
                when {
                    problem != null -> error = problem
                    !secret && SnippetRules.looksLikePassword(text) -> askMakeSecret = true
                    else -> save(secret)
                }
            }) { Text("Save") }
            if (id != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
    }

    if (askMakeSecret) {
        AlertDialog(
            onDismissRequest = { askMakeSecret = false },
            text = { Text("This looks like a password. Keep it encrypted and hidden as a password?") },
            confirmButton = {
                TextButton(onClick = {
                    askMakeSecret = false
                    if (name.isBlank()) { secret = true; error = SnippetRules.validate(name, text, true) } else save(true)
                }) { Text("Keep as password") }
            },
            dismissButton = { TextButton(onClick = { askMakeSecret = false; save(false) }) { Text("Keep as plain text") } },
        )
    }
    if (confirmDelete && id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text("Delete this snippet? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { withContext(Dispatchers.IO) { repository.delete(id) }; onDone() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
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
