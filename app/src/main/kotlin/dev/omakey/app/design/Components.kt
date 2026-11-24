package dev.omakey.app.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.omakey.core.icons.OmakeyIcons

/*
 * The shared components, one per component in the design file (Components page). Every screen in
 * Settings is built from these; a screen that needs something new adds it here first.
 */

/** A switch that only shows state: the row around it is the touch target (and the toggleable).
 * Ink track when on, tonal when off; 52 × 32. */
@Composable
fun OmakeySwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Omakey.colors
    val track by animateColorAsState(if (checked) c.ink else c.hairline, label = "track")
    val thumbOffset by animateDpAsState(if (checked) 24.dp else 4.dp, spring(stiffness = 800f), label = "thumb")
    val thumbSize by animateDpAsState(if (checked) 24.dp else 20.dp, label = "thumbSize")
    Box(
        modifier
            .size(52.dp, 32.dp)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.4f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset + (24.dp - thumbSize) / 2)
                .size(thumbSize)
                .background(if (checked) c.onInk else c.surface, CircleShape),
        )
    }
}

enum class ButtonKind { Primary, Tonal, Quiet, Destructive }

/** A verb on a button. One [ButtonKind.Primary] per screen; [ButtonKind.Tonal] for the rest;
 * [ButtonKind.Destructive] is quiet red text, never a red slab. */
@Composable
fun OmakeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Tonal,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val c = Omakey.colors
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.ink to c.onInk
        ButtonKind.Tonal -> c.tonal to c.ink
        ButtonKind.Quiet -> Color.Transparent to c.ink
        ButtonKind.Destructive -> Color.Transparent to c.danger
    }
    Row(
        modifier
            // A smaller button still gets a 48 dp touch area.
            .minimumInteractiveComponentSize()
            .heightIn(min = height)
            .clip(RoundedCornerShape(Radius.control))
            .background(if (enabled) bg else bg.copy(alpha = bg.alpha * 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val tint = if (enabled) fg else fg.copy(alpha = 0.45f)
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = Omakey.type.button, color = tint, maxLines = 1)
    }
}

/** A round 48 dp icon-only button, for row actions and headers. */
@Composable
fun IconAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Omakey.colors.ink, enabled: Boolean = true) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(22.dp))
    }
}

/** A 40 dp tonal tile holding a 20 dp icon: the leading mark on a Settings row. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, inverted: Boolean = false) {
    val c = Omakey.colors
    Box(
        modifier.size(40.dp).background(if (inverted) c.ink else c.tonal, RoundedCornerShape(Radius.control)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (inverted) c.onInk else c.ink, modifier = Modifier.size(20.dp))
    }
}

/** A group's name above its card: Label, muted, sentence case. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Omakey.type.label,
        color = Omakey.colors.muted,
        modifier = modifier.padding(start = 4.dp).semantics { heading() },
    )
}

/**
 * A white card of rows with hairlines between them, inset to where the text starts
 * ([dividerInset]: 70 for rows with an icon tile, 16 for plain rows). A child that takes no height
 * (a row hidden by its parent setting) gets no divider, so hiding a row never leaves a doubled line.
 */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, dividerInset: Dp = 16.dp, contentPadding: Dp = 0.dp, content: @Composable () -> Unit) {
    val c = Omakey.colors
    val lines = remember { DividerLines() }
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(c.surface)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                val x0 = contentPadding.toPx() + dividerInset.toPx()
                for (y in lines.ys) drawLine(c.hairline, Offset(x0, y), Offset(size.width, y), stroke)
            },
    ) { measurables, constraints ->
        val pad = contentPadding.roundToPx()
        val inner = constraints.copy(minHeight = 0, minWidth = 0, maxWidth = (constraints.maxWidth - 2 * pad).coerceAtLeast(0))
        val placeables = measurables.map { it.measure(inner) }
        val height = placeables.sumOf { it.height }
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            val ys = ArrayList<Float>()
            var y = 0
            var first = true
            for (p in placeables) {
                if (p.height == 0) { p.placeRelative(pad, y); continue }
                if (!first) ys += y.toFloat()
                first = false
                p.placeRelative(pad, y)
                y += p.height
            }
            lines.ys = ys
        }
    }
}

private class DividerLines {
    var ys: List<Float> by mutableStateOf(emptyList())
}

/** What sits at the end of a [SettingsRowLayout]. */
sealed interface RowTrailing {
    data object None : RowTrailing
    data object Chevron : RowTrailing
    data class Switch(val checked: Boolean, val enabled: Boolean = true) : RowTrailing
    data class Value(val text: String) : RowTrailing
    class Custom(val content: @Composable () -> Unit) : RowTrailing
}

/**
 * The one row shape every Settings row uses: optional icon tile, title over one line of muted
 * description, and a trailing switch, chevron or value. At least 64 tall. The caller supplies the
 * click or toggle behaviour through [modifier], so the whole row is the touch target.
 */
@Composable
fun SettingsRowLayout(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    trailing: RowTrailing = RowTrailing.None,
    enabled: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    leading: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    val c = Omakey.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) IconTile(icon)
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Omakey.type.row, color = if (enabled) c.ink else c.subtle)
            if (!description.isNullOrEmpty()) Text(description, style = Omakey.type.body, color = c.muted)
            below?.invoke()
        }
        when (trailing) {
            RowTrailing.None -> Unit
            RowTrailing.Chevron -> Icon(OmakeyIcons.ChevronRight, null, tint = c.subtle, modifier = Modifier.size(20.dp))
            is RowTrailing.Switch -> OmakeySwitch(trailing.checked, enabled = trailing.enabled && enabled)
            is RowTrailing.Value -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(trailing.text, style = Omakey.type.body, color = c.muted)
                Icon(OmakeyIcons.ChevronRight, null, tint = c.subtle, modifier = Modifier.size(20.dp))
            }
            is RowTrailing.Custom -> trailing.content()
        }
    }
}

/** A page's top: back chevron and the 22 sp title. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(OmakeyIcons.Back, "Back", onBack, Modifier.offset(x = (-8).dp))
        }
        Text(
            title,
            style = Omakey.type.title,
            color = Omakey.colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action?.invoke()
    }
}

/**
 * Two to four choices on a tonal track, the chosen one on a sliding white thumb. Labels stay on
 * one line; past four choices use [ChoiceTile]s instead.
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Omakey.colors
    // -1 when the current value isn't one of the options (a custom theme): no thumb is drawn.
    val index = options.indexOfFirst { it.first == selected }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(c.tonal, RoundedCornerShape(Radius.control))
            .padding(3.dp),
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * index.coerceAtLeast(0), spring(stiffness = 700f), label = "segment")
        if (index >= 0) Box(
            Modifier
                .offset(x = x)
                .width(segment)
                .fillMaxHeight()
                // In dark the card is darker than the track, so the thumb steps up instead.
                .background(if (c.isDark) Color(0xFF3A3A3A) else c.surface, RoundedCornerShape(11.dp)),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { (value, label) ->
                val isOn = value == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp))
                        .selectable(selected = isOn, role = Role.RadioButton, onClick = { onSelect(value) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = Omakey.type.body.copy(fontWeight = if (isOn) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium), color = if (isOn) c.ink else c.muted, maxLines = 1)
                }
            }
        }
    }
}

/** One of several choices drawn as a tile: tonal when not chosen, an ink ring when chosen. */
@Composable
fun ChoiceTile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null, content: @Composable () -> Unit) {
    val c = Omakey.colors
    Box(
        modifier
            .clip(RoundedCornerShape(Radius.control))
            .background(c.tonal)
            .border(BorderStroke(2.dp, if (selected) c.ink else Color.Transparent), RoundedCornerShape(Radius.control))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .then(if (label != null) Modifier.semantics { this.contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A text field: label above, tonal fill, ink focus ring. */
@Composable
fun OmakeyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    /** True on the page's ground (a white field); false inside a white card (a tonal field). */
    onGround: Boolean = false,
) {
    val c = Omakey.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring = when {
        isError -> c.danger
        focused -> c.ink
        else -> Color.Transparent
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, style = Omakey.type.label, color = c.muted, modifier = Modifier.padding(start = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            textStyle = (textStyle ?: Omakey.type.row.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)).copy(color = c.ink),
            cursorBrush = SolidColor(c.ink),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .background(if (onGround) c.surface else c.tonal, RoundedCornerShape(Radius.control))
                        .border(1.5.dp, ring, RoundedCornerShape(Radius.control))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    leading?.invoke()
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = Omakey.type.row.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.subtle)
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
        if (supporting != null) Text(supporting, style = Omakey.type.caption, color = if (isError) c.danger else c.muted, modifier = Modifier.padding(start = 4.dp))
    }
}

/** A slider in the system's colours: ink active track and thumb, tonal inactive track. */
@Composable
fun OmakeySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val c = Omakey.colors
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = range,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = c.ink,
            activeTrackColor = c.ink,
            inactiveTrackColor = c.tonal,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}

/**
 * The bottom sheet that replaces every pop-up dialog: a title, one sentence, and verb buttons.
 * The confirming verb is the ink button (or quiet red when it destroys something); the other
 * choice is tonal and says what keeping things as they are means ("Keep it", not "Cancel").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Keep it",
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    /** What the second button does when it isn't simply "close" (default: [onDismiss]). */
    onDismissButton: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val c = Omakey.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        contentColor = c.ink,
        shape = RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).background(c.hairline, CircleShape))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = Omakey.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
            if (message != null) Text(message, style = Omakey.type.body, color = c.muted)
            content?.invoke()
            Spacer(Modifier.height(12.dp))
            OmakeyButton(
                text = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
                kind = if (destructive) ButtonKind.Destructive else ButtonKind.Primary,
                modifier = Modifier.fillMaxWidth().then(
                    if (destructive) Modifier.background(c.danger.copy(alpha = 0.10f), RoundedCornerShape(Radius.control)) else Modifier,
                ),
            )
            OmakeyButton(text = dismissLabel, onClick = onDismissButton ?: onDismiss, kind = ButtonKind.Tonal, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A language's two-letter code in an outlined badge (EN, NE): languages are told apart by their
 * code, never by a flag (a flag is a country, not a language). */
@Composable
fun CodeBadge(code: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = Omakey.colors
    Box(
        modifier.size(size).background(c.tonal, RoundedCornerShape(Radius.control)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            code.take(2).uppercase(),
            style = Omakey.type.label.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(0.4f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = c.ink,
        )
    }
}

/** A language's mark: its flag where it has one (the developer's call: flags are what people
 * recognise at a glance), otherwise its two-letter code. */
@Composable
fun LanguageMark(localeId: String, modifier: Modifier = Modifier) {
    val flag = dev.omakey.core.locale.flagEmoji(localeId)
    if (flag == null) {
        CodeBadge(localeId.substringBefore('_'), modifier)
    } else {
        Box(modifier.size(40.dp).background(Omakey.colors.tonal, RoundedCornerShape(Radius.control)), contentAlignment = Alignment.Center) {
            Text(flag, fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
    }
}

/** A chip that is on or off: ink with a check when on, tonal when off. */
@Composable
fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Omakey.colors
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(Radius.key))
            .background(if (on) c.ink else c.tonal)
            .toggleableChip(on, onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (on) Icon(OmakeyIcons.Check, contentDescription = null, tint = c.onInk, modifier = Modifier.size(16.dp))
        Text(label, style = Omakey.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = if (on) c.onInk else c.ink)
    }
}

private fun Modifier.toggleableChip(on: Boolean, onClick: () -> Unit) =
    this.then(Modifier.clickable(role = Role.Checkbox, onClickLabel = if (on) "Turn off" else "Turn on", onClick = onClick))
        .semantics { stateDescription = if (on) "On" else "Off" }

/** A thin ink progress bar on a tonal track. */
@Composable
fun OmakeyProgress(fraction: Float, modifier: Modifier = Modifier) {
    val c = Omakey.colors
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.tonal)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.ink))
    }
}

/** One row of an [ActionSheet]. */
data class SheetAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/** A bottom sheet of actions on one thing (a theme, a snippet): a title, then icon rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val c = Omakey.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.ink,
        shape = RoundedCornerShape(topStart = Radius.card, topEnd = Radius.card),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).background(c.hairline, CircleShape)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).navigationBarsPadding()) {
            Text(
                title,
                style = Omakey.type.title,
                color = c.ink,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            )
            actions.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button, onClick = a.onClick)
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val tint = if (a.destructive) c.danger else c.ink
                    Icon(a.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(a.label, style = Omakey.type.row, color = tint)
                }
            }
        }
    }
}

/** The empty state: an icon tile, a sentence saying what the feature is for, and one action. */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon)
        Text(text, style = Omakey.type.body, color = Omakey.colors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

/** Spacing for a page's list: 16 dp gutter, 24 between groups. */
val PagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)
