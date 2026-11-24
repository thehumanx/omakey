package dev.omakey.app.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import dev.omakey.app.design.ButtonKind
import dev.omakey.app.design.LanguageMark
import dev.omakey.app.design.ConfirmSheet
import dev.omakey.app.design.Omakey
import dev.omakey.app.design.OmakeyButton
import dev.omakey.app.design.OmakeyProgress
import dev.omakey.app.design.RowTrailing
import dev.omakey.app.design.SettingsRowLayout
import dev.omakey.app.design.ToggleChip
import dev.omakey.core.icons.OmakeyIcons
import dev.omakey.app.BuildConfig
import dev.omakey.app.languages.LanguagePacks
import dev.omakey.core.icons.PhosphorLineCheck
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.locale.flagEmoji
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
@OptIn(ExperimentalLayoutApi::class)
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
                onSuccess = { "${it.nativeName} is installed and on." },
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
        // One card row per language: the title row toggles it; the layout chips and pack line
        // under it are their own targets, so a tap that misses a chip can't switch it off.
        Column {
            SettingsRowLayout(
                title = locale.nativeName,
                description = when {
                    isLastEnabled -> "${locale.displayName} · one language stays on"
                    locale.nativeName != locale.displayName -> locale.displayName
                    else -> null
                },
                leading = { LanguageMark(locale.id) },
                trailing = RowTrailing.Switch(enabled),
                horizontalPadding = 0.dp,
                modifier = Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = { on ->
                    if (!on && isLastEnabled) return@toggleable
                    localePreferences.setEnabled(if (on) settings.enabledIds + locale.id else settings.enabledIds - locale.id)
                }),
            )
            if ((enabled && locale.letterLayoutChoices.size > 1) || installed != null) {
                Column(Modifier.padding(start = 54.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (enabled && locale.letterLayoutChoices.size > 1) {
                        // One chip per layout, each on or off: the globe key, the language picker
                        // and the spacebar only go through the ones left on. One must stay.
                        val disabled = settings.disabledLayoutsOf(locale.id)
                        val onCount = locale.letterLayoutChoices.count { it.id !in disabled }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            locale.letterLayoutChoices.forEach { layout ->
                                val on = layout.id !in disabled
                                ToggleChip(dev.omakey.core.layout.shortName(layout), on, onClick = {
                                    if (on && onCount == 1) return@ToggleChip
                                    localePreferences.setLayoutEnabled(locale.id, layout.id, !on)
                                })
                            }
                        }
                    }
                    if (installed != null) {
                        val manifest = remember(installed) { languagePacks.manifestOf(locale.id) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Where the words came from, with each source's licence: the packs'
                            // CC BY / CC BY-SA data needs this attribution (decisions §66).
                            Text(
                                text = "Pack $installed" + (manifest?.let { m ->
                                    " · Data: ${m.license.ifBlank { "see sources" }}. " + m.sources.joinToString("; ") { src ->
                                        if (src.license.isBlank()) src.name else "${src.name} (${src.license})"
                                    }
                                } ?: ""),
                                style = Omakey.type.caption,
                                color = Omakey.colors.muted,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            OmakeyButton("Remove", { confirmRemove = locale }, kind = ButtonKind.Destructive, height = 40.dp)
                        }
                    }
                }
            }
        }
    }

    // --- Downloadable packs -------------------------------------------------------------------
    when (indexState) {
        IndexState.NOT_FETCHED, IndexState.FAILED -> SettingsRowLayout(
            title = "Get more languages",
            description = if (indexState == IndexState.FAILED) "Couldn't reach the list. Tap to try again." else "Only this list and the packs you pick use the internet.",
            leading = { dev.omakey.app.design.IconTile(OmakeyIcons.Download) },
            trailing = RowTrailing.Chevron,
            horizontalPadding = 0.dp,
            modifier = Modifier.clickable(role = Role.Button) {
                indexState = IndexState.LOADING
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { languagePacks.fetchIndex() } }
                    index = result.getOrNull()
                    indexState = if (result.isSuccess) IndexState.LOADED else IndexState.FAILED
                    result.exceptionOrNull()?.let { message = it.message }
                }
            },
        )
        IndexState.LOADING -> SettingsRowLayout(
            title = "Getting the list…",
            leading = { dev.omakey.app.design.IconTile(OmakeyIcons.Download) },
            horizontalPadding = 0.dp,
        )
        IndexState.LOADED -> {
            val offers = index?.packs.orEmpty().filter { entry ->
                val installed = installedVersions[entry.id]
                installed == null || isNewerPackVersion(entry.packVersion, installed)
            }
            if (offers.isEmpty()) {
                SettingsRowLayout(
                    title = "You have every language",
                    description = "All up to date.",
                    leading = { dev.omakey.app.design.IconTile(OmakeyIcons.Check) },
                    horizontalPadding = 0.dp,
                )
            }
            offers.forEach { entry ->
                val fraction = progress[entry.id]
                val isUpdate = installedVersions[entry.id] != null
                val compatible = entry.packFormat == PackManifest.FORMAT && entry.minAppVersionCode <= BuildConfig.VERSION_CODE
                SettingsRowLayout(
                    title = entry.nativeName,
                    description = if (compatible) {
                        "${"%.1f".format(entry.sizeBytes / 1_000_000.0)} MB · ${entry.license}"
                    } else {
                        "Needs a newer omakey"
                    },
                    leading = { LanguageMark(entry.id) },
                    horizontalPadding = 0.dp,
                    below = if (fraction == null) null else { { OmakeyProgress(fraction, Modifier.padding(top = 8.dp)) } },
                    trailing = if (!compatible) RowTrailing.None else RowTrailing.Custom {
                        OmakeyButton(
                            text = if (isUpdate) "Update" else "Get",
                            onClick = { install(entry) },
                            enabled = fraction == null,
                            kind = ButtonKind.Tonal,
                            height = 40.dp,
                        )
                    },
                )
            }
        }
    }

    ClickableSettingRow(
        key = SettingKey.INSTALL_FROM_FILE,
        description = "Add a language from a .zip you have.",
        onClick = { filePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
    )

    message?.let { SettingBlock { SettingDescription(it) } }

    confirmRemove?.let { locale ->
        ConfirmSheet(
            title = "Remove ${locale.nativeName}?",
            message = "Words omakey learned in ${locale.displayName} are kept, in case you add it again.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                languagePacks.uninstall(locale.id, localePreferences)
                refreshInstalled()
                confirmRemove = null
            },
            onDismiss = { confirmRemove = null },
        )
    }

    pendingFile?.let { uri ->
        ConfirmSheet(
            title = "Install from a file?",
            message = "Packs from omakey's list are verified. A file can't be, so only install one you trust. Packs hold words, never code.",
            confirmLabel = "Install",
            dismissLabel = "Don't install",
            onConfirm = {
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
                        onSuccess = { "${it.nativeName} is installed and on." },
                        onFailure = { "Couldn't install that file: ${it.message}" },
                    )
                }
            },
            onDismiss = { pendingFile = null },
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
