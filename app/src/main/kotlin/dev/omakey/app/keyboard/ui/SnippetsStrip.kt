package dev.omakey.app.keyboard.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.app.keyboard.KeyboardFeedback
import dev.omakey.app.keyboard.KeyboardViewModel
import dev.omakey.core.snippets.SnippetRules
import dev.omakey.core.theme.OmakeyTheme
import dev.omakey.core.theme.toComposeColor

/**
 * The Snippets page above the keys (Fleksy's "Hotkeys", unlimited): one chip per snippet in the
 * user's order, tap to type, long-press to edit, and a "+" at the end to add one. Secrets show
 * the mask and their name, never their text, and are left out while the phone is locked (see
 * [KeyboardViewModel.snippetChips]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SnippetsTabContent(
    theme: OmakeyTheme,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    viewModel: KeyboardViewModel,
    feedback: KeyboardFeedback,
    showKeyBackgrounds: Boolean,
) {
    val snippets by viewModel.snippetChips.collectAsState()
    val isGridMode = dev.omakey.core.theme.LocalKeyboardLayoutMode.current == dev.omakey.core.theme.LayoutMode.GRID

    @Composable
    fun Chip(label: String, description: String, onTap: () -> Unit, onLongPress: (() -> Unit)?) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val surface = theme.stripCellSurface(isGridMode, showKeyBackgrounds || !isGridMode, pressed)
        Box(
            Modifier
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .background(surface.toComposeColor(), RoundedCornerShape(if (isGridMode) 0.dp else 16.dp))
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { feedback.onKeyPress(); onTap() },
                    onLongClick = onLongPress?.let { { feedback.onKeyPress(); it() } },
                )
                .semantics { contentDescription = description }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = theme.labelOn(surface).toComposeColor(),
                fontFamily = fontFamily,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(snippets, key = { it.id }) { s ->
            Chip(
                label = SnippetRules.chipLabel(s),
                description = if (s.secret) "Secret snippet, ${s.name}" else "Snippet, ${SnippetRules.chipLabel(s)}",
                onTap = { viewModel.onSnippetTap(s) },
                onLongPress = { viewModel.editSnippet(s.id) },
            )
        }
        item(key = "add") {
            Chip(
                label = if (snippets.isEmpty()) "+ Add a snippet (email, phone, address…)" else "+",
                description = "Add a snippet",
                onTap = { viewModel.editSnippet(null) },
                onLongPress = null,
            )
        }
    }
}
