package dev.omakey.app.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.omakey.app.BuildConfig
import dev.omakey.app.languages.LanguagePacks
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.locale.LocalePreferences
import dev.omakey.core.pack.PackIndex
import dev.omakey.core.pack.PackIndexEntry
import dev.omakey.core.pack.PackInstaller
import dev.omakey.core.pack.PackManifest
import dev.omakey.core.pack.isNewerPackVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings → Languages (AGENTS.md §66 Phases 5 and 7).
 *
 * - A switch per available language; with two or more on, the keyboard grows a language key. The
 *   last one can't be turned off — a keyboard with no language has nothing to type in, and
 *   re-enabling English behind the user's back would be worse than refusing.
 * - Installed packs can be removed, and show their data licence and sources.
 * - "Get more languages" fetches the signed index — only when tapped; nothing here touches the
 *   network on its own — and offers each pack not installed, or newer than the installed one.
 * - "Install from file" takes a pack from storage, after a warning: there's no signed checksum to
 *   hold it to, so it is only as trustworthy as wherever it came from.
 */
@Composable
internal fun LanguagesSection(localePreferences: LocalePreferences, languagePacks: LanguagePacks) {
    val settings by localePreferences.settings.collectAsState()
    val available by languagePacks.registry.available.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var index by remember { mutableStateOf<PackIndex?>(null) }
    var indexState by remember { mutableStateOf(IndexState.NOT_FETCHED) }
    var message by remember { mutableStateOf<String?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }
    var installedVersions by remember { mutableStateOf(languagePacks.installedVersions()) }
    var confirmRemove by remember { mutableStateOf<KeyboardLocale?>(null) }
    var pendingFile by remember { mutableStateOf<Uri?>(null) }

    fun refreshInstalled() {
        installedVersions = languagePacks.installedVersions()
    }

    fun install(entry: PackIndexEntry) {
        progress[entry.id] = 0f
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    languagePacks.downloadAndInstall(entry, localePreferences) { fraction -> progress[entry.id] = fraction }
                }
            }
            progress.remove(entry.id)
            refreshInstalled()
            message = result.fold(
                onSuccess = { "${it.nativeName} installed and switched on." },
                onFailure = { "Couldn't install ${entry.nativeName}: ${it.message}" },
            )
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingFile = uri
    }

    // --- Enabled / installed languages ---------------------------------------------------------
    available.forEach { locale ->
        val enabled = locale.id in settings.enabledIds
        val isLastEnabled = enabled && settings.enabledIds.count { id -> available.any { it.id == id } } == 1
        val installed = installedVersions[locale.id]
        SettingToggle(
            title = locale.nativeName,
            description = buildString {
                append(locale.displayName)
                if (installed != null) append(" · pack $installed")
                if (isLastEnabled) append(" — at least one language has to stay on.")
            },
            checked = enabled,
            onCheckedChange = { on ->
                if (!on && isLastEnabled) return@SettingToggle
                localePreferences.setEnabled(if (on) settings.enabledIds + locale.id else settings.enabledIds - locale.id)
            },
        )
        if (enabled && locale.letterLayoutChoices.size > 1) {
            val chosen = settings.layoutChoices[locale.id] ?: locale.letterLayout.id
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                locale.letterLayoutChoices.forEachIndexed { i, layout ->
                    SegmentedButton(
                        selected = layout.id == chosen,
                        onClick = { localePreferences.setLayoutChoice(locale.id, layout.id) },
                        shape = SegmentedButtonDefaults.itemShape(i, locale.letterLayoutChoices.size),
                        icon = {},
                    ) { Text(layoutName(layout)) }
                }
            }
        }
        if (installed != null) {
            val manifest = remember(installed) { languagePacks.manifestOf(locale.id) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = manifest?.let { m ->
                        "Data: ${m.license.ifBlank { "see sources" }}. " + m.sources.joinToString("; ") { s ->
                            if (s.license.isBlank()) s.name else "${s.name} (${s.license})"
                        }
                    }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                TextButton(onClick = { confirmRemove = locale }) { Text("Remove") }
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // --- Downloadable packs -------------------------------------------------------------------
    when (indexState) {
        IndexState.NOT_FETCHED, IndexState.FAILED -> ClickableSettingRow(
            title = "Get more languages",
            description = if (indexState == IndexState.FAILED) {
                "Couldn't reach the language list. Tap to try again."
            } else {
                "Downloads the list of language packs from omakey's GitHub releases. Only this, and " +
                    "the packs you choose, use the network."
            },
            onClick = {
                indexState = IndexState.LOADING
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { languagePacks.fetchIndex() } }
                    index = result.getOrNull()
                    indexState = if (result.isSuccess) IndexState.LOADED else IndexState.FAILED
                    result.exceptionOrNull()?.let { message = it.message }
                }
            },
        )
        IndexState.LOADING -> Text("Fetching the language list…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        IndexState.LOADED -> {
            val offers = index?.packs.orEmpty().filter { entry ->
                val installed = installedVersions[entry.id]
                installed == null || isNewerPackVersion(entry.packVersion, installed)
            }
            if (offers.isEmpty()) {
                Text("Every available language is installed and up to date.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
            offers.forEach { entry ->
                val fraction = progress[entry.id]
                val isUpdate = installedVersions[entry.id] != null
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.nativeName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${entry.displayName} · ${"%.1f".format(entry.sizeBytes / 1_000_000.0)} MB · ${entry.license}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val compatible = entry.packFormat == PackManifest.FORMAT && entry.minAppVersionCode <= BuildConfig.VERSION_CODE
                        if (compatible) {
                            TextButton(enabled = fraction == null, onClick = { install(entry) }) {
                                Text(if (isUpdate) "Update" else "Download")
                            }
                        } else {
                            Text(
                                "Needs a newer omakey",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    ClickableSettingRow(
        title = "Install from file",
        description = "Install a language pack (.zip) you already have — for example one you built yourself.",
        onClick = { filePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
    )

    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }

    confirmRemove?.let { locale ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${locale.nativeName}?") },
            text = { Text("Words the keyboard learned in ${locale.displayName} are kept, in case you install it again.") },
            confirmButton = {
                TextButton(onClick = {
                    languagePacks.uninstall(locale.id, localePreferences)
                    refreshInstalled()
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }

    pendingFile?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingFile = null },
            title = { Text("Install from file?") },
            text = {
                Text(
                    "Packs from omakey's language list are checked against a signed checksum. A file " +
                        "can't be — it's checked for being a well-formed pack, but not for where it " +
                        "came from. Language packs contain data only, never code; still, only install " +
                        "ones you trust.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingFile = null
                    message = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val copy = File(context.cacheDir, "language-file.zip")
                                try {
                                    context.contentResolver.openInputStream(uri)!!.use { input ->
                                        copy.outputStream().use { out -> copyAtMost(input, out, PackInstaller.MAX_TOTAL_BYTES) }
                                    }
                                    languagePacks.installFromFile(copy, localePreferences)
                                } finally {
                                    copy.delete()
                                }
                            }
                        }
                        refreshInstalled()
                        message = result.fold(
                            onSuccess = { "${it.nativeName} installed and switched on." },
                            onFailure = { "Couldn't install that file: ${it.message}" },
                        )
                    }
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { pendingFile = null }) { Text("Cancel") } },
        )
    }
}

private enum class IndexState { NOT_FETCHED, LOADING, LOADED, FAILED }

/** Copies at most [limit] bytes, failing rather than filling storage with whatever a picker hands
 * over — the installer's own caps only apply once the file is on disk. */
private fun copyAtMost(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        total += read
        if (total > limit) throw java.io.IOException("file is too large to be a language pack")
        output.write(buffer, 0, read)
    }
}

/** "AZERTY", "QWERTY" — a layout named by its first six letters, the way keyboards are. */
private fun layoutName(layout: dev.omakey.core.layout.KeyboardLayout): String =
    layout.rows.firstOrNull()?.keys.orEmpty()
        .filter { it.keyType == dev.omakey.core.layout.KeyType.CHARACTER }
        .take(6)
        .joinToString("") { it.label }
        .uppercase()
