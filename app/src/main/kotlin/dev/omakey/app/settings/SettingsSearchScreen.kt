package dev.omakey.app.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.omakey.core.search.SearchHit

private val EXAMPLE_QUERIES = listOf("vibration", "theme", "autocorrect", "clipboard")

/** Full-screen search over every setting. Colours come from the Settings Material scheme only. */
@Composable
internal fun SettingsSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpen: (SettingKey) -> Unit,
    listState: LazyListState,
) {
    val search = remember { SettingsIndex.searchFor(Build.VERSION.SDK_INT) }
    val hits = remember(query) { search.search(query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Only when starting fresh: coming Back to a results list shouldn't pop the keyboard over it.
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    // Held as a TextFieldValue so the cursor goes to the end when a chip fills the field (and
    // after rotation); with a plain String it stays at 0 and typing lands in front of the word.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(query, TextRange(query.length)))
    }
    val setQuery: (String) -> Unit = { text ->
        field = TextFieldValue(text, TextRange(text.length))
        onQueryChange(text)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SearchField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != query) onQueryChange(it.text)
            },
            onClear = { setQuery("") },
            onBack = onBack,
            onSearch = { keyboard?.hide() },
            focus = focus,
        )
        Spacer(Modifier.height(8.dp))
        // Always composed (empty while blank) so each change is an update to the same live region,
        // which TalkBack announces; a node that appears only on the first letter often isn't.
        Text(
            text = when {
                query.isBlank() -> ""
                hits.isEmpty() -> "No results"
                hits.size == 1 -> "1 result"
                else -> "${hits.size} results"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        when {
            query.isBlank() -> EmptyState(onPick = setQuery)
            hits.isEmpty() -> NoResults(query)
            else -> LazyColumn(Modifier.fillMaxWidth(), state = listState) {
                items(hits, key = { it.id }) { hit ->
                    ResultRow(hit, onClick = { keyboard?.hide(); onOpen(hit.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** Material3's TextField (not BasicTextField) so TalkBack reads the typed text, with
 * "Search settings" as the field's label/hint rather than replacing it. */
@Composable
private fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    focus: FocusRequester,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text("Search settings") },
        leadingIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = dev.omakey.core.icons.PhosphorLineArrowLeft,
                    contentDescription = "Close search",
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        trailingIcon = if (value.text.isEmpty()) null else {
            {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = dev.omakey.core.icons.PhosphorLineX,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Find any setting by name.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            EXAMPLE_QUERIES.forEach { example ->
                AssistChip(
                    onClick = { onPick(example) },
                    label = { Text(example) },
                )
            }
        }
    }
}

@Composable
private fun NoResults(query: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "No settings match “${query.trim()}”.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Try a shorter word, or browse the pages.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ResultRow(hit: SearchHit<SettingKey>, onClick: () -> Unit) {
    val key = hit.id
    val crumb = "${key.section.page.title} › ${key.section.title}" +
        (hit.matchedKeyword?.let { " · matches “$it”" } ?: "")
    val spoken = "${key.title}, ${key.section.page.title}, ${key.section.title} section"
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick(label = "Open") { onClick(); true }
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(text = highlighted(key.title, hit.titleHighlights), style = MaterialTheme.typography.bodyLarge)
        Text(text = crumb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun highlighted(title: String, ranges: List<IntRange>): AnnotatedString = buildAnnotatedString {
    append(title)
    ranges.forEach { r ->
        if (r.first >= 0 && r.last < title.length) addStyle(SpanStyle(fontWeight = FontWeight.Bold), r.first, r.last + 1)
    }
}
