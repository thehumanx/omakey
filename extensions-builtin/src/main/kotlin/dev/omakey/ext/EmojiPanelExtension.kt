package dev.omakey.ext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omakey.core.theme.LocalOmakeyTheme
import dev.omakey.extapi.ExtensionContext
import dev.omakey.extapi.ExtensionHost
import dev.omakey.extapi.ExtensionIcon
import dev.omakey.extapi.OmakeyExtension

private fun dev.omakey.core.theme.ColorSpec.toComposeColor() = Color(argb.toInt())

/** Bundled static emoji data, no network. Recents tracking deferred past v1 (small nice-to-have). */
class EmojiPanelExtension : OmakeyExtension {
    override val id = "builtin.emoji"
    override val displayName = "Emoji"
    override val icon = ExtensionIcon.Emoji("😊")

    private var extensionContext: ExtensionContext? = null

    override fun onAttach(context: ExtensionContext) {
        extensionContext = context
    }

    override fun onDetach() {
        extensionContext = null
    }

    @Composable
    override fun PanelContent(host: ExtensionHost) {
        var categoryIndex by remember { mutableStateOf(0) }
        val category = EmojiCategories.all[categoryIndex]
        // Text() with no explicit color falls back to LocalContentColor, which defaults to black
        // outside of a Material theming ancestor — invisible against the dark keyboard background.
        // ExtensionPanelSlot provides LocalOmakeyTheme around every extension's content, so this
        // is always available here.
        val textColor = LocalOmakeyTheme.current.keyTextColor.toComposeColor()
        var dragAccumX by remember { mutableStateOf(0f) }

        Column(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp)
                    // Left/right swipe on the grid itself steps to the previous/next category —
                    // same left="back"/right="forward" convention as the rest of the app's
                    // gesture surface (see GestureStateMachine). LazyVerticalGrid only scrolls
                    // vertically, so a horizontal drag detector here doesn't fight its own
                    // scrolling.
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragAccumX = 0f },
                            onDragEnd = {
                                if (dragAccumX <= -SWIPE_THRESHOLD_PX) {
                                    categoryIndex = (categoryIndex + 1).coerceAtMost(EmojiCategories.all.lastIndex)
                                } else if (dragAccumX >= SWIPE_THRESHOLD_PX) {
                                    categoryIndex = (categoryIndex - 1).coerceAtLeast(0)
                                }
                                dragAccumX = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragAccumX += dragAmount
                        }
                    },
            ) {
                items(category.emoji) { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 26.sp,
                        modifier = Modifier
                            .clickable { host.insertText(emoji) }
                            .padding(6.dp),
                    )
                }
            }

            // ABC returns to the normal keyboard, category icons jump between emoji groups,
            // backspace deletes without needing to leave the panel — matches the standard layout
            // convention (Fleksy, Gboard, etc.) instead of only exposing this via the top tab bar.
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(Color.Black.copy(alpha = 0.15f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable { host.close() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "ABC", color = textColor, fontSize = 14.sp)
                }
                // Scrollable, not weighted — 10 categories (9 Unicode groups + Special
                // characters) no longer fit fixed-width in a single row without squeezing icons
                // down to illegibility on a phone-width keyboard.
                LazyRow(Modifier.weight(1f).fillMaxHeight()) {
                    lazyRowItems(EmojiCategories.all) { cat ->
                        val isSelected = cat == category
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(36.dp)
                                .clickable { categoryIndex = EmojiCategories.all.indexOf(cat) }
                                .background(if (isSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Missing color here made every tab icon (not just "#") default to
                            // black-on-dark and effectively disappear against the panel background.
                            Text(text = cat.icon, color = textColor, fontSize = 18.sp)
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable { extensionContext?.textEditor?.deleteBackward(1) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "⌫", color = textColor, fontSize = 18.sp)
                }
            }
        }
    }

    private companion object {
        const val SWIPE_THRESHOLD_PX = 80f
    }
}

private data class EmojiCategory(val icon: String, val emoji: List<String>)

private object EmojiCategories {
    val Smileys = EmojiCategory(
        icon = "😀",
        emoji = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
            "🫠", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "☺️",
            "😚", "😙", "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗",
            "🤭", "🫢", "🫣", "🤫", "🤔", "🫡", "🤐", "🤨", "😐", "😑",
            "😶", "🫥", "😶‍🌫️", "😏", "😒", "🙄", "😬", "😮‍💨", "🤥", "🫨",
            "🙂‍↔️", "🙂‍↕️", "😌", "😔", "😪", "🤤", "😴", "🫩", "😷", "🤒",
            "🤕", "🤢", "🤮", "🤧", "🥵", "🥶", "🥴", "😵", "😵‍💫", "🤯",
            "🤠", "🥳", "🥸", "😎", "🤓", "🧐", "😕", "🫤", "😟", "🙁",
            "☹️", "😮", "😯", "😲", "😳", "🥺", "🥹", "😦", "😧", "😨",
            "😰", "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓", "😩",
            "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "☠️",
            "💩", "🤡", "👹", "👺", "👻", "👽", "👾", "🤖", "😺", "😸",
            "😹", "😻", "😼", "😽", "🙀", "😿", "😾", "🙈", "🙉", "🙊",
            "💌", "💘", "💝", "💖", "💗", "💓", "💞", "💕", "💟", "❣️",
            "💔", "❤️‍🔥", "❤️‍🩹", "❤️", "🩷", "🧡", "💛", "💚", "💙", "🩵",
            "💜", "🤎", "🖤", "🩶", "🤍", "💋", "💯", "💢", "💥", "💫",
            "💦", "💨", "🕳️", "💬", "👁️‍🗨️", "🗨️", "🗯️", "💭", "💤",
        ),
    )
    val People = EmojiCategory(
        icon = "👋",
        emoji = listOf(
            "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "🫷",
            "🫸", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
            "👈", "👉", "👆", "🖕", "👇", "☝️", "🫵", "👍", "👎", "✊",
            "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏",
            "✍️", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👂", "🦻",
            "👃", "🧠", "🫀", "🫁", "🦷", "🦴", "👀", "👁️", "👅", "👄",
            "🫦", "👶", "🧒", "👦", "👧", "🧑", "👱", "👨", "🧔", "🧔‍♂️",
            "🧔‍♀️", "👨‍🦰", "👨‍🦱", "👨‍🦳", "👨‍🦲", "👩", "👩‍🦰", "🧑‍🦰", "👩‍🦱", "🧑‍🦱",
            "👩‍🦳", "🧑‍🦳", "👩‍🦲", "🧑‍🦲", "👱‍♀️", "👱‍♂️", "🧓", "👴", "👵", "🙍",
            "🙍‍♂️", "🙍‍♀️", "🙎", "🙎‍♂️", "🙎‍♀️", "🙅", "🙅‍♂️", "🙅‍♀️", "🙆", "🙆‍♂️",
            "🙆‍♀️", "💁", "💁‍♂️", "💁‍♀️", "🙋", "🙋‍♂️", "🙋‍♀️", "🧏", "🧏‍♂️", "🧏‍♀️",
            "🙇", "🙇‍♂️", "🙇‍♀️", "🤦", "🤦‍♂️", "🤦‍♀️", "🤷", "🤷‍♂️", "🤷‍♀️", "🧑‍⚕️",
            "👨‍⚕️", "👩‍⚕️", "🧑‍🎓", "👨‍🎓", "👩‍🎓", "🧑‍🏫", "👨‍🏫", "👩‍🏫", "🧑‍⚖️", "👨‍⚖️",
            "👩‍⚖️", "🧑‍🌾", "👨‍🌾", "👩‍🌾", "🧑‍🍳", "👨‍🍳", "👩‍🍳", "🧑‍🔧", "👨‍🔧", "👩‍🔧",
            "🧑‍🏭", "👨‍🏭", "👩‍🏭", "🧑‍💼", "👨‍💼", "👩‍💼", "🧑‍🔬", "👨‍🔬", "👩‍🔬", "🧑‍💻",
            "👨‍💻", "👩‍💻", "🧑‍🎤", "👨‍🎤", "👩‍🎤", "🧑‍🎨", "👨‍🎨", "👩‍🎨", "🧑‍✈️", "👨‍✈️",
            "👩‍✈️", "🧑‍🚀", "👨‍🚀", "👩‍🚀", "🧑‍🚒", "👨‍🚒", "👩‍🚒", "👮", "👮‍♂️", "👮‍♀️",
            "🕵️", "🕵️‍♂️", "🕵️‍♀️", "💂", "💂‍♂️", "💂‍♀️", "🥷", "👷", "👷‍♂️", "👷‍♀️",
            "🫅", "🤴", "👸", "👳", "👳‍♂️", "👳‍♀️", "👲", "🧕", "🤵", "🤵‍♂️",
            "🤵‍♀️", "👰", "👰‍♂️", "👰‍♀️", "🤰", "🫃", "🫄", "🤱", "👩‍🍼", "👨‍🍼",
            "🧑‍🍼", "👼", "🎅", "🤶", "🧑‍🎄", "🦸", "🦸‍♂️", "🦸‍♀️", "🦹", "🦹‍♂️",
            "🦹‍♀️", "🧙", "🧙‍♂️", "🧙‍♀️", "🧚", "🧚‍♂️", "🧚‍♀️", "🧛", "🧛‍♂️", "🧛‍♀️",
            "🧜", "🧜‍♂️", "🧜‍♀️", "🧝", "🧝‍♂️", "🧝‍♀️", "🧞", "🧞‍♂️", "🧞‍♀️", "🧟",
            "🧟‍♂️", "🧟‍♀️", "🧌", "💆", "💆‍♂️", "💆‍♀️", "💇", "💇‍♂️", "💇‍♀️", "🚶",
            "🚶‍♂️", "🚶‍♀️", "🚶‍➡️", "🚶‍♀️‍➡️", "🚶‍♂️‍➡️", "🧍", "🧍‍♂️", "🧍‍♀️", "🧎", "🧎‍♂️",
            "🧎‍♀️", "🧎‍➡️", "🧎‍♀️‍➡️", "🧎‍♂️‍➡️", "🧑‍🦯", "🧑‍🦯‍➡️", "👨‍🦯", "👨‍🦯‍➡️", "👩‍🦯", "👩‍🦯‍➡️",
            "🧑‍🦼", "🧑‍🦼‍➡️", "👨‍🦼", "👨‍🦼‍➡️", "👩‍🦼", "👩‍🦼‍➡️", "🧑‍🦽", "🧑‍🦽‍➡️", "👨‍🦽", "👨‍🦽‍➡️",
            "👩‍🦽", "👩‍🦽‍➡️", "🏃", "🏃‍♂️", "🏃‍♀️", "🏃‍➡️", "🏃‍♀️‍➡️", "🏃‍♂️‍➡️", "💃", "🕺",
            "🕴️", "👯", "👯‍♂️", "👯‍♀️", "🧖", "🧖‍♂️", "🧖‍♀️", "🧗", "🧗‍♂️", "🧗‍♀️",
            "🤺", "🏇", "⛷️", "🏂", "🏌️", "🏌️‍♂️", "🏌️‍♀️", "🏄", "🏄‍♂️", "🏄‍♀️",
            "🚣", "🚣‍♂️", "🚣‍♀️", "🏊", "🏊‍♂️", "🏊‍♀️", "⛹️", "⛹️‍♂️", "⛹️‍♀️", "🏋️",
            "🏋️‍♂️", "🏋️‍♀️", "🚴", "🚴‍♂️", "🚴‍♀️", "🚵", "🚵‍♂️", "🚵‍♀️", "🤸", "🤸‍♂️",
            "🤸‍♀️", "🤼", "🤼‍♂️", "🤼‍♀️", "🤽", "🤽‍♂️", "🤽‍♀️", "🤾", "🤾‍♂️", "🤾‍♀️",
            "🤹", "🤹‍♂️", "🤹‍♀️", "🧘", "🧘‍♂️", "🧘‍♀️", "🛀", "🛌", "🧑‍🤝‍🧑", "👭",
            "👫", "👬", "💏", "👩‍❤️‍💋‍👨", "👨‍❤️‍💋‍👨", "👩‍❤️‍💋‍👩", "💑", "👩‍❤️‍👨", "👨‍❤️‍👨", "👩‍❤️‍👩",
            "👨‍👩‍👦", "👨‍👩‍👧", "👨‍👩‍👧‍👦", "👨‍👩‍👦‍👦", "👨‍👩‍👧‍👧", "👨‍👨‍👦", "👨‍👨‍👧", "👨‍👨‍👧‍👦", "👨‍👨‍👦‍👦", "👨‍👨‍👧‍👧",
            "👩‍👩‍👦", "👩‍👩‍👧", "👩‍👩‍👧‍👦", "👩‍👩‍👦‍👦", "👩‍👩‍👧‍👧", "👨‍👦", "👨‍👦‍👦", "👨‍👧", "👨‍👧‍👦", "👨‍👧‍👧",
            "👩‍👦", "👩‍👦‍👦", "👩‍👧", "👩‍👧‍👦", "👩‍👧‍👧", "🗣️", "👤", "👥", "🫂", "👪",
            "🧑‍🧑‍🧒", "🧑‍🧑‍🧒‍🧒", "🧑‍🧒", "🧑‍🧒‍🧒", "👣", "🫆",
        ),
    )
    val Animals = EmojiCategory(
        icon = "🐶",
        emoji = listOf(
            "🐵", "🐒", "🦍", "🦧", "🐶", "🐕", "🦮", "🐕‍🦺", "🐩", "🐺",
            "🦊", "🦝", "🐱", "🐈", "🐈‍⬛", "🦁", "🐯", "🐅", "🐆", "🐴",
            "🫎", "🫏", "🐎", "🦄", "🦓", "🦌", "🦬", "🐮", "🐂", "🐃",
            "🐄", "🐷", "🐖", "🐗", "🐽", "🐏", "🐑", "🐐", "🐪", "🐫",
            "🦙", "🦒", "🐘", "🦣", "🦏", "🦛", "🐭", "🐁", "🐀", "🐹",
            "🐰", "🐇", "🐿️", "🦫", "🦔", "🦇", "🐻", "🐻‍❄️", "🐨", "🐼",
            "🦥", "🦦", "🦨", "🦘", "🦡", "🐾", "🦃", "🐔", "🐓", "🐣",
            "🐤", "🐥", "🐦", "🐧", "🕊️", "🦅", "🦆", "🦢", "🦉", "🦤",
            "🪶", "🦩", "🦚", "🦜", "🪽", "🐦‍⬛", "🪿", "🐦‍🔥", "🐸", "🐊",
            "🐢", "🦎", "🐍", "🐲", "🐉", "🦕", "🦖", "🐳", "🐋", "🐬",
            "🦭", "🐟", "🐠", "🐡", "🦈", "🐙", "🐚", "🪸", "🪼", "🦀",
            "🦞", "🦐", "🦑", "🦪", "🐌", "🦋", "🐛", "🐜", "🐝", "🪲",
            "🐞", "🦗", "🪳", "🕷️", "🕸️", "🦂", "🦟", "🪰", "🪱", "🦠",
            "💐", "🌸", "💮", "🪷", "🏵️", "🌹", "🥀", "🌺", "🌻", "🌼",
            "🌷", "🪻", "🌱", "🪴", "🌲", "🌳", "🌴", "🌵", "🌾", "🌿",
            "☘️", "🍀", "🍁", "🍂", "🍃", "🪹", "🪺", "🍄", "🪾",
        ),
    )
    val Food = EmojiCategory(
        icon = "🍔",
        emoji = listOf(
            "🍇", "🍈", "🍉", "🍊", "🍋", "🍋‍🟩", "🍌", "🍍", "🥭", "🍎",
            "🍏", "🍐", "🍑", "🍒", "🍓", "🫐", "🥝", "🍅", "🫒", "🥥",
            "🥑", "🍆", "🥔", "🥕", "🌽", "🌶️", "🫑", "🥒", "🥬", "🥦",
            "🧄", "🧅", "🥜", "🫘", "🌰", "🫚", "🫛", "🍄‍🟫", "🫜", "🍞",
            "🥐", "🥖", "🫓", "🥨", "🥯", "🥞", "🧇", "🧀", "🍖", "🍗",
            "🥩", "🥓", "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🫔",
            "🥙", "🧆", "🥚", "🍳", "🥘", "🍲", "🫕", "🥣", "🥗", "🍿",
            "🧈", "🧂", "🥫", "🍱", "🍘", "🍙", "🍚", "🍛", "🍜", "🍝",
            "🍠", "🍢", "🍣", "🍤", "🍥", "🥮", "🍡", "🥟", "🥠", "🥡",
            "🍦", "🍧", "🍨", "🍩", "🍪", "🎂", "🍰", "🧁", "🥧", "🍫",
            "🍬", "🍭", "🍮", "🍯", "🍼", "🥛", "☕", "🫖", "🍵", "🍶",
            "🍾", "🍷", "🍸", "🍹", "🍺", "🍻", "🥂", "🥃", "🫗", "🥤",
            "🧋", "🧃", "🧉", "🧊", "🥢", "🍽️", "🍴", "🥄", "🔪", "🫙",
            "🏺",
        ),
    )
    val Travel = EmojiCategory(
        icon = "✈️",
        emoji = listOf(
            "🌍", "🌎", "🌏", "🌐", "🗺️", "🗾", "🧭", "🏔️", "⛰️", "🌋",
            "🗻", "🏕️", "🏖️", "🏜️", "🏝️", "🏞️", "🏟️", "🏛️", "🏗️", "🧱",
            "🪨", "🪵", "🛖", "🏘️", "🏚️", "🏠", "🏡", "🏢", "🏣", "🏤",
            "🏥", "🏦", "🏨", "🏩", "🏪", "🏫", "🏬", "🏭", "🏯", "🏰",
            "💒", "🗼", "🗽", "⛪", "🕌", "🛕", "🕍", "⛩️", "🕋", "⛲",
            "⛺", "🌁", "🌃", "🏙️", "🌄", "🌅", "🌆", "🌇", "🌉", "♨️",
            "🎠", "🛝", "🎡", "🎢", "💈", "🎪", "🚂", "🚃", "🚄", "🚅",
            "🚆", "🚇", "🚈", "🚉", "🚊", "🚝", "🚞", "🚋", "🚌", "🚍",
            "🚎", "🚐", "🚑", "🚒", "🚓", "🚔", "🚕", "🚖", "🚗", "🚘",
            "🚙", "🛻", "🚚", "🚛", "🚜", "🏎️", "🏍️", "🛵", "🦽", "🦼",
            "🛺", "🚲", "🛴", "🛹", "🛼", "🚏", "🛣️", "🛤️", "🛢️", "⛽",
            "🛞", "🚨", "🚥", "🚦", "🛑", "🚧", "⚓", "🛟", "⛵", "🛶",
            "🚤", "🛳️", "⛴️", "🛥️", "🚢", "✈️", "🛩️", "🛫", "🛬", "🪂",
            "💺", "🚁", "🚟", "🚠", "🚡", "🛰️", "🚀", "🛸", "🛎️", "🧳",
            "⌛", "⏳", "⌚", "⏰", "⏱️", "⏲️", "🕰️", "🕛", "🕧", "🕐",
            "🕜", "🕑", "🕝", "🕒", "🕞", "🕓", "🕟", "🕔", "🕠", "🕕",
            "🕡", "🕖", "🕢", "🕗", "🕣", "🕘", "🕤", "🕙", "🕥", "🕚",
            "🕦", "🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘", "🌙",
            "🌚", "🌛", "🌜", "🌡️", "☀️", "🌝", "🌞", "🪐", "⭐", "🌟",
            "🌠", "🌌", "☁️", "⛅", "⛈️", "🌤️", "🌥️", "🌦️", "🌧️", "🌨️",
            "🌩️", "🌪️", "🌫️", "🌬️", "🌀", "🌈", "🌂", "☂️", "☔", "⛱️",
            "⚡", "❄️", "☃️", "⛄", "☄️", "🔥", "💧", "🌊",
        ),
    )
    val Activities = EmojiCategory(
        icon = "⚽",
        emoji = listOf(
            "🎃", "🎄", "🎆", "🎇", "🧨", "✨", "🎈", "🎉", "🎊", "🎋",
            "🎍", "🎎", "🎏", "🎐", "🎑", "🧧", "🎀", "🎁", "🎗️", "🎟️",
            "🎫", "🎖️", "🏆", "🏅", "🥇", "🥈", "🥉", "⚽", "⚾", "🥎",
            "🏀", "🏐", "🏈", "🏉", "🎾", "🥏", "🎳", "🏏", "🏑", "🏒",
            "🥍", "🏓", "🏸", "🥊", "🥋", "🥅", "⛳", "⛸️", "🎣", "🤿",
            "🎽", "🎿", "🛷", "🥌", "🎯", "🪀", "🪁", "🔫", "🎱", "🔮",
            "🪄", "🎮", "🕹️", "🎰", "🎲", "🧩", "🧸", "🪅", "🪩", "🪆",
            "♠️", "♥️", "♦️", "♣️", "♟️", "🃏", "🀄", "🎴", "🎭", "🖼️",
            "🎨", "🧵", "🪡", "🧶", "🪢",
        ),
    )
    val Objects = EmojiCategory(
        icon = "💡",
        emoji = listOf(
            "👓", "🕶️", "🥽", "🥼", "🦺", "👔", "👕", "👖", "🧣", "🧤",
            "🧥", "🧦", "👗", "👘", "🥻", "🩱", "🩲", "🩳", "👙", "👚",
            "🪭", "👛", "👜", "👝", "🛍️", "🎒", "🩴", "👞", "👟", "🥾",
            "🥿", "👠", "👡", "🩰", "👢", "🪮", "👑", "👒", "🎩", "🎓",
            "🧢", "🪖", "⛑️", "📿", "💄", "💍", "💎", "🔇", "🔈", "🔉",
            "🔊", "📢", "📣", "📯", "🔔", "🔕", "🎼", "🎵", "🎶", "🎙️",
            "🎚️", "🎛️", "🎤", "🎧", "📻", "🎷", "🪗", "🎸", "🎹", "🎺",
            "🎻", "🪕", "🥁", "🪘", "🪇", "🪈", "🪉", "📱", "📲", "☎️",
            "📞", "📟", "📠", "🔋", "🪫", "🔌", "💻", "🖥️", "🖨️", "⌨️",
            "🖱️", "🖲️", "💽", "💾", "💿", "📀", "🧮", "🎥", "🎞️", "📽️",
            "🎬", "📺", "📷", "📸", "📹", "📼", "🔍", "🔎", "🕯️", "💡",
            "🔦", "🏮", "🪔", "📔", "📕", "📖", "📗", "📘", "📙", "📚",
            "📓", "📒", "📃", "📜", "📄", "📰", "🗞️", "📑", "🔖", "🏷️",
            "💰", "🪙", "💴", "💵", "💶", "💷", "💸", "💳", "🧾", "💹",
            "✉️", "📧", "📨", "📩", "📤", "📥", "📦", "📫", "📪", "📬",
            "📭", "📮", "🗳️", "✏️", "✒️", "🖋️", "🖊️", "🖌️", "🖍️", "📝",
            "💼", "📁", "📂", "🗂️", "📅", "📆", "🗒️", "🗓️", "📇", "📈",
            "📉", "📊", "📋", "📌", "📍", "📎", "🖇️", "📏", "📐", "✂️",
            "🗃️", "🗄️", "🗑️", "🔒", "🔓", "🔏", "🔐", "🔑", "🗝️", "🔨",
            "🪓", "⛏️", "⚒️", "🛠️", "🗡️", "⚔️", "💣", "🪃", "🏹", "🛡️",
            "🪚", "🔧", "🪛", "🔩", "⚙️", "🗜️", "⚖️", "🦯", "🔗", "⛓️‍💥",
            "⛓️", "🪝", "🧰", "🧲", "🪜", "🪏", "⚗️", "🧪", "🧫", "🧬",
            "🔬", "🔭", "📡", "💉", "🩸", "💊", "🩹", "🩼", "🩺", "🩻",
            "🚪", "🛗", "🪞", "🪟", "🛏️", "🛋️", "🪑", "🚽", "🪠", "🚿",
            "🛁", "🪤", "🪒", "🧴", "🧷", "🧹", "🧺", "🧻", "🪣", "🧼",
            "🫧", "🪥", "🧽", "🧯", "🛒", "🚬", "⚰️", "🪦", "⚱️", "🧿",
            "🪬", "🗿", "🪧", "🪪",
        ),
    )
    val Symbols = EmojiCategory(
        icon = "❤️",
        emoji = listOf(
            "🏧", "🚮", "🚰", "♿", "🚹", "🚺", "🚻", "🚼", "🚾", "🛂",
            "🛃", "🛄", "🛅", "⚠️", "🚸", "⛔", "🚫", "🚳", "🚭", "🚯",
            "🚱", "🚷", "📵", "🔞", "☢️", "☣️", "⬆️", "↗️", "➡️", "↘️",
            "⬇️", "↙️", "⬅️", "↖️", "↕️", "↔️", "↩️", "↪️", "⤴️", "⤵️",
            "🔃", "🔄", "🔙", "🔚", "🔛", "🔜", "🔝", "🛐", "⚛️", "🕉️",
            "✡️", "☸️", "☯️", "✝️", "☦️", "☪️", "☮️", "🕎", "🔯", "🪯",
            "♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑",
            "♒", "♓", "⛎", "🔀", "🔁", "🔂", "▶️", "⏩", "⏭️", "⏯️",
            "◀️", "⏪", "⏮️", "🔼", "⏫", "🔽", "⏬", "⏸️", "⏹️", "⏺️",
            "⏏️", "🎦", "🔅", "🔆", "📶", "🛜", "📳", "📴", "♀️", "♂️",
            "⚧️", "✖️", "➕", "➖", "➗", "🟰", "♾️", "‼️", "⁉️", "❓",
            "❔", "❕", "❗", "〰️", "💱", "💲", "⚕️", "♻️", "⚜️", "🔱",
            "📛", "🔰", "⭕", "✅", "☑️", "✔️", "❌", "❎", "➰", "➿",
            "〽️", "✳️", "✴️", "❇️", "©️", "®️", "™️", "🫟", "#️⃣", "*️⃣",
            "0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣",
            "🔟", "🔠", "🔡", "🔢", "🔣", "🔤", "🅰️", "🆎", "🅱️", "🆑",
            "🆒", "🆓", "ℹ️", "🆔", "Ⓜ️", "🆕", "🆖", "🅾️", "🆗", "🅿️",
            "🆘", "🆙", "🆚", "🈁", "🈂️", "🈷️", "🈶", "🈯", "🉐", "🈹",
            "🈚", "🈲", "🉑", "🈸", "🈴", "🈳", "㊗️", "㊙️", "🈺", "🈵",
            "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "🟤", "⚫", "⚪", "🟥",
            "🟧", "🟨", "🟩", "🟦", "🟪", "🟫", "⬛", "⬜", "◼️", "◻️",
            "◾", "◽", "▪️", "▫️", "🔶", "🔷", "🔸", "🔹", "🔺", "🔻",
            "💠", "🔘", "🔳", "🔲",
        ),
    )
    val Flags = EmojiCategory(
        icon = "🏳️",
        emoji = listOf(
            "🏁", "🚩", "🎌", "🏴", "🏳️", "🏳️‍🌈", "🏳️‍⚧️", "🏴‍☠️", "🇦🇨", "🇦🇩",
            "🇦🇪", "🇦🇫", "🇦🇬", "🇦🇮", "🇦🇱", "🇦🇲", "🇦🇴", "🇦🇶", "🇦🇷", "🇦🇸",
            "🇦🇹", "🇦🇺", "🇦🇼", "🇦🇽", "🇦🇿", "🇧🇦", "🇧🇧", "🇧🇩", "🇧🇪", "🇧🇫",
            "🇧🇬", "🇧🇭", "🇧🇮", "🇧🇯", "🇧🇱", "🇧🇲", "🇧🇳", "🇧🇴", "🇧🇶", "🇧🇷",
            "🇧🇸", "🇧🇹", "🇧🇻", "🇧🇼", "🇧🇾", "🇧🇿", "🇨🇦", "🇨🇨", "🇨🇩", "🇨🇫",
            "🇨🇬", "🇨🇭", "🇨🇮", "🇨🇰", "🇨🇱", "🇨🇲", "🇨🇳", "🇨🇴", "🇨🇵", "🇨🇶",
            "🇨🇷", "🇨🇺", "🇨🇻", "🇨🇼", "🇨🇽", "🇨🇾", "🇨🇿", "🇩🇪", "🇩🇬", "🇩🇯",
            "🇩🇰", "🇩🇲", "🇩🇴", "🇩🇿", "🇪🇦", "🇪🇨", "🇪🇪", "🇪🇬", "🇪🇭", "🇪🇷",
            "🇪🇸", "🇪🇹", "🇪🇺", "🇫🇮", "🇫🇯", "🇫🇰", "🇫🇲", "🇫🇴", "🇫🇷", "🇬🇦",
            "🇬🇧", "🇬🇩", "🇬🇪", "🇬🇫", "🇬🇬", "🇬🇭", "🇬🇮", "🇬🇱", "🇬🇲", "🇬🇳",
            "🇬🇵", "🇬🇶", "🇬🇷", "🇬🇸", "🇬🇹", "🇬🇺", "🇬🇼", "🇬🇾", "🇭🇰", "🇭🇲",
            "🇭🇳", "🇭🇷", "🇭🇹", "🇭🇺", "🇮🇨", "🇮🇩", "🇮🇪", "🇮🇱", "🇮🇲", "🇮🇳",
            "🇮🇴", "🇮🇶", "🇮🇷", "🇮🇸", "🇮🇹", "🇯🇪", "🇯🇲", "🇯🇴", "🇯🇵", "🇰🇪",
            "🇰🇬", "🇰🇭", "🇰🇮", "🇰🇲", "🇰🇳", "🇰🇵", "🇰🇷", "🇰🇼", "🇰🇾", "🇰🇿",
            "🇱🇦", "🇱🇧", "🇱🇨", "🇱🇮", "🇱🇰", "🇱🇷", "🇱🇸", "🇱🇹", "🇱🇺", "🇱🇻",
            "🇱🇾", "🇲🇦", "🇲🇨", "🇲🇩", "🇲🇪", "🇲🇫", "🇲🇬", "🇲🇭", "🇲🇰", "🇲🇱",
            "🇲🇲", "🇲🇳", "🇲🇴", "🇲🇵", "🇲🇶", "🇲🇷", "🇲🇸", "🇲🇹", "🇲🇺", "🇲🇻",
            "🇲🇼", "🇲🇽", "🇲🇾", "🇲🇿", "🇳🇦", "🇳🇨", "🇳🇪", "🇳🇫", "🇳🇬", "🇳🇮",
            "🇳🇱", "🇳🇴", "🇳🇵", "🇳🇷", "🇳🇺", "🇳🇿", "🇴🇲", "🇵🇦", "🇵🇪", "🇵🇫",
            "🇵🇬", "🇵🇭", "🇵🇰", "🇵🇱", "🇵🇲", "🇵🇳", "🇵🇷", "🇵🇸", "🇵🇹", "🇵🇼",
            "🇵🇾", "🇶🇦", "🇷🇪", "🇷🇴", "🇷🇸", "🇷🇺", "🇷🇼", "🇸🇦", "🇸🇧", "🇸🇨",
            "🇸🇩", "🇸🇪", "🇸🇬", "🇸🇭", "🇸🇮", "🇸🇯", "🇸🇰", "🇸🇱", "🇸🇲", "🇸🇳",
            "🇸🇴", "🇸🇷", "🇸🇸", "🇸🇹", "🇸🇻", "🇸🇽", "🇸🇾", "🇸🇿", "🇹🇦", "🇹🇨",
            "🇹🇩", "🇹🇫", "🇹🇬", "🇹🇭", "🇹🇯", "🇹🇰", "🇹🇱", "🇹🇲", "🇹🇳", "🇹🇴",
            "🇹🇷", "🇹🇹", "🇹🇻", "🇹🇼", "🇹🇿", "🇺🇦", "🇺🇬", "🇺🇲", "🇺🇳", "🇺🇸",
            "🇺🇾", "🇺🇿", "🇻🇦", "🇻🇨", "🇻🇪", "🇻🇬", "🇻🇮", "🇻🇳", "🇻🇺", "🇼🇫",
            "🇼🇸", "🇽🇰", "🇾🇪", "🇾🇹", "🇿🇦", "🇿🇲", "🇿🇼", "🏴󠁧󠁢󠁥󠁮󠁧󠁿", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "🏴󠁧󠁢󠁷󠁬󠁳󠁿",
        ),
    )
    val Special = EmojiCategory(
        icon = "#",
        emoji = listOf(
            "°", "%", "§", "†", "‡", "•", "‣", "∙", "©", "®",
            "™", "℠", "№", "#", "$", "€", "£", "¥", "¢", "₹",
            "₩", "₽", "±", "×", "÷", "=", "≠", "≈", "≤", "≥",
            "∞", "√", "∑", "∏", "Ω", "∆", "π", "µ", "→", "←",
            "↑", "↓", "↔", "¶", "«", "»", "‹", "›", "„", "“",
            "”", "‘", "’", "—", "–", "…", "¦", "¬", "¯", "´",
            "¨", "ˆ", "˜", "¤", "ƒ", "¹", "²", "³", "¼", "½",
            "¾", "α", "β", "γ", "δ",
        ),
    )

    val all = listOf(Smileys, People, Animals, Food, Travel, Activities, Objects, Symbols, Flags, Special)
}
