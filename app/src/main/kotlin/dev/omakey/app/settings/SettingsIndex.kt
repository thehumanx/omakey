package dev.omakey.app.settings

import dev.omakey.core.search.SearchDocument
import dev.omakey.core.search.SettingsSearch

/*
 * The one list of what Settings contains: pages, the section cards on each page (in on-screen
 * order), and every always-shown setting row. The rows read their titles from here and the pages
 * are built from the section list, so what search finds can't drift from what the screen shows.
 */

/** The four groups on the Settings home, in order. */
internal enum class SettingsGroup(val title: String) {
    LOOK("Look"),
    TYPING("Typing"),
    ABOVE_THE_KEYS("Above the keys"),
    MORE("More"),
}

/**
 * The Settings pages, grouped by what the user is trying to do rather than which preference class
 * stores it (design pass, 2026-11-24): everything about how keys look under Keys, everything above
 * the keys together, clipboard on its own page instead of split between Privacy and the bar.
 * Snippets isn't a page here: its home row opens the Snippets screen directly.
 */
internal enum class SettingsPage(val title: String, val summary: String, val group: SettingsGroup) {
    THEME("Theme", "Colours, your own themes", SettingsGroup.LOOK),
    KEYS("Keys", "Layout, backgrounds, key popup, emoji", SettingsGroup.LOOK),
    FONT("Font", "The letters on the keys", SettingsGroup.LOOK),
    SIZE_POSITION("Size & position", "Height, edges, split", SettingsGroup.LOOK),
    CORRECTIONS("Corrections & suggestions", "Autocorrect, capitals, punctuation", SettingsGroup.TYPING),
    GESTURES("Gestures", "Swipe to delete, space, select", SettingsGroup.TYPING),
    LANGUAGES("Languages", "Languages, layouts, downloads", SettingsGroup.TYPING),
    BAR_PAGES("Extension bar pages", "Suggestions, snippets, numbers, tools", SettingsGroup.ABOVE_THE_KEYS),
    QUICK_ACCESS("Quick access", "Show, hide and order tiles", SettingsGroup.ABOVE_THE_KEYS),
    CLIPBOARD("Clipboard", "What you copied", SettingsGroup.ABOVE_THE_KEYS),
    SOUND("Sound & vibration", "Key sounds, vibration", SettingsGroup.MORE),
    PRIVACY("Privacy", "Learning and learned words", SettingsGroup.MORE),
    ACCESSIBILITY("Accessibility", "Screen reader mode", SettingsGroup.MORE),
    ABOUT("About", "Version and updates", SettingsGroup.MORE),
}

/** One section card. Declared in the order the cards appear on their page: the page renders
 * them from this list, and search scrolls to them by their position in it. A section whose title
 * repeats its page's shows no label. [bare] sections lay out their own cards. */
internal enum class SettingsSectionKey(val title: String, val page: SettingsPage, val bare: Boolean = false) {
    THEME("Theme", SettingsPage.THEME, bare = true),
    LAYOUT_STYLE("Layout style", SettingsPage.KEYS),
    KEYS("Look of the keys", SettingsPage.KEYS),
    BUTTONS("Beside the spacebar", SettingsPage.KEYS),
    SYMBOLS("Symbols", SettingsPage.KEYS),
    NUMBER_PAD("Number pad", SettingsPage.KEYS),
    EMOJI("Emoji", SettingsPage.KEYS),
    FONT("Font", SettingsPage.FONT),
    SIZE_POSITION("Size & position", SettingsPage.SIZE_POSITION),
    CORRECTIONS("Corrections", SettingsPage.CORRECTIONS),
    PUNCTUATION("Capitals & punctuation", SettingsPage.CORRECTIONS),
    GESTURES("Gestures", SettingsPage.GESTURES),
    LANGUAGES("Languages", SettingsPage.LANGUAGES),
    SUGGESTION_BAR("Extension bar pages", SettingsPage.BAR_PAGES, bare = true),
    QUICK_ACCESS("Quick access", SettingsPage.QUICK_ACCESS, bare = true),
    CLIPBOARD("Clipboard", SettingsPage.CLIPBOARD),
    SOUND_HAPTICS("Sound & vibration", SettingsPage.SOUND),
    LEARNING("Learning", SettingsPage.PRIVACY),
    WHAT_LEAVES("What leaves your phone", SettingsPage.PRIVACY),
    ACCESSIBILITY("Accessibility", SettingsPage.ACCESSIBILITY),
    UPDATES("Updates", SettingsPage.ABOUT),
}

/**
 * Every setting row that is always on screen. Rows shown only while another setting is on
 * (haptic strength, sound volume, the number pad's field kinds, "Get more languages") have no
 * entry of their own: their words are keywords of the parent, so a result never opens a page
 * where its row is hidden. [sectionLevel] entries are pickers with no row title; their highlight
 * goes on the section header. Keywords are lowercase synonyms the title doesn't already contain.
 */
internal enum class SettingKey(
    val title: String,
    val section: SettingsSectionKey,
    val keywords: List<String> = emptyList(),
    val sectionLevel: Boolean = false,
    val minSdk: Int = 0,
) {
    LAYOUT_STYLE("Layout style", SettingsSectionKey.LAYOUT_STYLE, listOf("normal", "grid"), sectionLevel = true),
    THEME("Theme", SettingsSectionKey.THEME, listOf("dark", "light", "colour", "color", "accent", "custom", "editor"), sectionLevel = true),
    SYSTEM_ACCENT("Pick accent color from system", SettingsSectionKey.THEME, listOf("material you", "dynamic", "colour"), minSdk = 31),
    KEY_BACKGROUNDS("Key backgrounds", SettingsSectionKey.KEYS, listOf("box", "border")),
    HOME_ROW_HIGHLIGHT("Home row highlight", SettingsSectionKey.KEYS, listOf("stripe", "asdf")),
    SPLIT_DUPLICATE_KEYS("Repeat the middle keys on both sides", SettingsSectionKey.SIZE_POSITION, listOf("split", "foldable", "tablet", "landscape", "duplicate")),
    SECONDARY_SYMBOLS("Show secondary symbols", SettingsSectionKey.KEYS, listOf("hint", "digits", "long press")),
    ALWAYS_CAPITALS("Always show capital letters", SettingsSectionKey.KEYS, listOf("uppercase", "lowercase", "caps", "keycaps")),
    FONT("Font", SettingsSectionKey.FONT, listOf("typeface"), sectionLevel = true),
    SIZE_POSITION("Keyboard size & position", SettingsSectionKey.SIZE_POSITION, listOf("height", "resize", "one-handed", "bigger", "smaller")),
    EDGE_PADDING("Add padding", SettingsSectionKey.SIZE_POSITION, listOf("gap", "edge", "curved", "margin")),
    BAR_SUGGESTIONS("Suggestions", SettingsSectionKey.SUGGESTION_BAR, listOf("strip", "page", "language button")),
    BAR_SNIPPETS("Snippets", SettingsSectionKey.SUGGESTION_BAR, listOf("bar page", "snippet", "hotkey", "quick input", "saved text", "manage snippets", "email", "phone", "password", "address", "login")),
    BAR_NUMBERS("Numbers", SettingsSectionKey.SUGGESTION_BAR, listOf("digits", "number row")),
    BAR_TEXT_TOOLS("Text tools", SettingsSectionKey.SUGGESTION_BAR, listOf("undo", "redo", "copy", "cut", "paste", "select all")),
    QUICK_ACCESS_TILES("Quick access", SettingsSectionKey.QUICK_ACCESS, listOf("quick settings", "tiles", "shortcuts", "toggles", "panel"), sectionLevel = true),
    AUTOFILL("Password manager logins", SettingsSectionKey.SUGGESTION_BAR, listOf("autofill suggestions", "password", "login", "bitwarden")),
    SPACEBAR_SIDE_KEY("Key next to the spacebar", SettingsSectionKey.BUTTONS, listOf("period", "comma", "full stop", "dot", ".")),
    EMOJI_SLOT_KEY("Key left of the spacebar", SettingsSectionKey.BUTTONS, listOf("emoji", "swap", "switch", "globe", "language", "comma")),
    TAP_PREVIEW("Key press popup", SettingsSectionKey.KEYS, listOf("preview", "bubble", "magnify", "slim", "lift", "classic")),
    FLEKSY_SYMBOLS("Fleksy symbol layout", SettingsSectionKey.SYMBOLS, listOf("keep fleksy secondary keys layout", "?123", "symbols page")),
    POPUP_SYMBOLS("Long-press symbols", SettingsSectionKey.SYMBOLS, listOf("custom", "hold", "special characters")),
    NUMBER_PAD("Number pad", SettingsSectionKey.NUMBER_PAD, listOf("numbers", "phone", "date", "time", "pin", "digits", "numeric")),
    EMOJI_SKIN_TONE("Emoji skin tone", SettingsSectionKey.EMOJI, listOf("skin", "colour", "color")),
    LANGUAGES("Languages", SettingsSectionKey.LANGUAGES, listOf("download", "pack", "layout", "get more languages", "remove"), sectionLevel = true),
    INSTALL_FROM_FILE("Install from file", SettingsSectionKey.LANGUAGES, listOf("zip", "language pack")),
    AUTOCORRECT("Autocorrect", SettingsSectionKey.CORRECTIONS, listOf("spelling", "typo", "fix")),
    NEXT_WORD_PREDICTION("Next-word prediction", SettingsSectionKey.CORRECTIONS, listOf("suggestions", "predict")),
    AUTO_CAPITALIZE("Auto-capitalize", SettingsSectionKey.PUNCTUATION, listOf("capital", "sentence", "uppercase")),
    DOUBLE_TAP_PERIOD("Double-tap space for period", SettingsSectionKey.PUNCTUATION, listOf("full stop", "dot")),
    SPACE_AFTER_PUNCTUATION("Space after punctuation", SettingsSectionKey.PUNCTUATION, listOf("comma", "auto space")),
    SWIPE_DISTANCE("Swipe distance", SettingsSectionKey.GESTURES, listOf("sensitivity", "threshold")),
    LONG_PRESS_SPECIAL("Long press for special characters", SettingsSectionKey.GESTURES, listOf("accent", "popup", "diacritic", "hold")),
    SWIPE_RIGHT_FOR_SPACE("Swipe right for space", SettingsSectionKey.GESTURES),
    SWIPE_LEFT_SPACE_STEP("Delete the space separately", SettingsSectionKey.GESTURES, listOf("count space as its own swipe-left delete", "swipe left", "delete word", "backspace")),
    SWIPE_SPACEBAR_LANGUAGE("Swipe the spacebar to change language", SettingsSectionKey.GESTURES, listOf("switch")),
    LEARN_FROM_TYPING("Learn from my typing", SettingsSectionKey.LEARNING, listOf("incognito", "dictionary", "learning")),
    LEARNED_WORDS("Learned words", SettingsSectionKey.LEARNING, listOf("dictionary", "remove", "delete", "forget")),
    SAVE_CLIPBOARD_HISTORY("Save clipboard history", SettingsSectionKey.CLIPBOARD, listOf("copy", "paste")),
    CLIPBOARD_HISTORY("Clipboard history", SettingsSectionKey.CLIPBOARD, listOf("copy", "paste", "clear", "pinned")),
    WHAT_LEAVES("What leaves your phone", SettingsSectionKey.WHAT_LEAVES, listOf("privacy", "network", "internet", "data", "offline"), sectionLevel = true),
    HAPTIC_FEEDBACK("Vibration", SettingsSectionKey.SOUND_HAPTICS, listOf("haptic", "vibrate", "buzz", "strength")),
    KEY_SOUNDS("Key sounds", SettingsSectionKey.SOUND_HAPTICS, listOf("click", "audio", "volume")),
    ACCESSIBLE_MODE("Accessible mode", SettingsSectionKey.ACCESSIBILITY, listOf("talkback", "screen reader", "blind")),
    AUTO_UPDATE_CHECKS("Automatic update checks", SettingsSectionKey.UPDATES, listOf("version", "notification")),
    CHECK_FOR_UPDATES("Check for updates", SettingsSectionKey.UPDATES, listOf("version", "release", "upgrade")),
}

internal object SettingsIndex {
    fun sectionsOf(page: SettingsPage): List<SettingsSectionKey> =
        SettingsSectionKey.entries.filter { it.page == page }

    fun pagesIn(group: SettingsGroup): List<SettingsPage> = SettingsPage.entries.filter { it.group == group }

    /** Search over the entries this Android version shows. */
    fun searchFor(sdkInt: Int): SettingsSearch<SettingKey> = SettingsSearch(
        SettingKey.entries.filter { sdkInt >= it.minSdk && (dev.omakey.app.BuildConfig.SELF_UPDATE || it.section != SettingsSectionKey.UPDATES) }.map { key ->
            SearchDocument(key, key.title, key.keywords, listOf(key.section.title, key.section.page.title))
        },
    )
}
