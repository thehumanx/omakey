package dev.omakey.app.settings

import dev.omakey.core.search.SearchDocument
import dev.omakey.core.search.SettingsSearch

/*
 * The one list of what Settings contains: pages, the section cards on each page (in on-screen
 * order), and every always-shown setting row. The rows read their titles from here and the pages
 * are built from the section list, so what search finds can't drift from what the screen shows.
 */

/**
 * The Settings pages. Settings used to be one long scroll holding every option, which had become
 * too long to find anything in; now the home screen lists these and each opens its own page.
 * Grouped by what the user is trying to do, not by which preference class stores it.
 */
internal enum class SettingsPage(val title: String, val summary: String) {
    APPEARANCE("Appearance", "Layout style, theme, key look, font"),
    KEYBOARD("Keyboard", "Size & position, buttons, emoji skin tone"),
    LANGUAGES("Languages", "Languages, layouts, downloads"),
    TYPING("Typing", "Autocorrect, prediction, capitals, punctuation"),
    GESTURES("Gestures", "Swipes, long-press, swipe distance"),
    PRIVACY("Privacy & data", "Learning, learned words, clipboard history"),
    SOUND("Sound & haptics", "Key sounds, vibration"),
    ACCESSIBILITY("Accessibility", "Screen reader mode"),
    ABOUT("About", "Version and updates"),
}

/** One section card. Declared in the order the cards appear on their page: the page renders
 * them from this list, and search scrolls to them by their position in it. */
internal enum class SettingsSectionKey(val title: String, val page: SettingsPage) {
    LAYOUT_STYLE("Layout style", SettingsPage.APPEARANCE),
    THEME("Theme", SettingsPage.APPEARANCE),
    KEYS("Keys", SettingsPage.APPEARANCE),
    FONT("Font", SettingsPage.APPEARANCE),
    SIZE_POSITION("Size & position", SettingsPage.KEYBOARD),
    SUGGESTION_BAR("Suggestion bar", SettingsPage.KEYBOARD),
    BUTTONS("Buttons", SettingsPage.KEYBOARD),
    SYMBOLS("Symbols", SettingsPage.KEYBOARD),
    NUMBER_PAD("Number pad", SettingsPage.KEYBOARD),
    EMOJI("Emoji", SettingsPage.KEYBOARD),
    LANGUAGES("Languages", SettingsPage.LANGUAGES),
    CORRECTIONS("Corrections", SettingsPage.TYPING),
    PUNCTUATION("Punctuation & capitals", SettingsPage.TYPING),
    GESTURES("Gestures", SettingsPage.GESTURES),
    LEARNING("Learning", SettingsPage.PRIVACY),
    CLIPBOARD("Clipboard", SettingsPage.PRIVACY),
    WHAT_LEAVES("What leaves your phone", SettingsPage.PRIVACY),
    SOUND_HAPTICS("Sound & haptics", SettingsPage.SOUND),
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
    SECONDARY_SYMBOLS("Show secondary symbols", SettingsSectionKey.KEYS, listOf("hint", "digits", "long press")),
    ALWAYS_CAPITALS("Always show capital letters", SettingsSectionKey.KEYS, listOf("uppercase", "lowercase", "caps", "keycaps")),
    FONT("Font", SettingsSectionKey.FONT, listOf("typeface"), sectionLevel = true),
    SIZE_POSITION("Keyboard size & position", SettingsSectionKey.SIZE_POSITION, listOf("height", "resize", "one-handed", "bigger", "smaller")),
    EDGE_PADDING("Add padding", SettingsSectionKey.SIZE_POSITION, listOf("gap", "edge", "curved", "margin")),
    BAR_SUGGESTIONS("Suggestions", SettingsSectionKey.SUGGESTION_BAR, listOf("strip", "page", "language button")),
    BAR_NUMBERS("Numbers", SettingsSectionKey.SUGGESTION_BAR, listOf("digits", "number row")),
    BAR_TEXT_TOOLS("Text tools", SettingsSectionKey.SUGGESTION_BAR, listOf("undo", "redo", "copy", "cut", "paste", "select all")),
    AUTOFILL("Autofill suggestions", SettingsSectionKey.SUGGESTION_BAR, listOf("password", "login", "bitwarden")),
    SWAP_EMOJI_LANGUAGE("Swap emoji and language buttons", SettingsSectionKey.BUTTONS, listOf("switch", "globe")),
    TAP_PREVIEW("Show key press popup", SettingsSectionKey.BUTTONS, listOf("preview", "bubble", "magnify")),
    FLEKSY_SYMBOLS("Keep Fleksy secondary keys layout", SettingsSectionKey.SYMBOLS, listOf("?123", "symbols page")),
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
    SWIPE_LEFT_SPACE_STEP("Count space as its own swipe-left delete", SettingsSectionKey.GESTURES, listOf("delete word", "backspace")),
    SWIPE_SPACEBAR_LANGUAGE("Swipe the spacebar to change language", SettingsSectionKey.GESTURES, listOf("switch")),
    LEARN_FROM_TYPING("Learn from my typing", SettingsSectionKey.LEARNING, listOf("incognito", "dictionary", "learning")),
    LEARNED_WORDS("Learned words", SettingsSectionKey.LEARNING, listOf("dictionary", "remove", "delete", "forget")),
    SAVE_CLIPBOARD_HISTORY("Save clipboard history", SettingsSectionKey.CLIPBOARD, listOf("copy", "paste")),
    CLIPBOARD_HISTORY("Clipboard history", SettingsSectionKey.CLIPBOARD, listOf("copy", "paste", "clear", "pinned")),
    WHAT_LEAVES("What leaves your phone", SettingsSectionKey.WHAT_LEAVES, listOf("privacy", "network", "internet", "data", "offline"), sectionLevel = true),
    HAPTIC_FEEDBACK("Haptic feedback", SettingsSectionKey.SOUND_HAPTICS, listOf("vibration", "vibrate", "buzz", "strength")),
    KEY_SOUNDS("Key sounds", SettingsSectionKey.SOUND_HAPTICS, listOf("click", "audio", "volume")),
    ACCESSIBLE_MODE("Accessible mode", SettingsSectionKey.ACCESSIBILITY, listOf("talkback", "screen reader", "blind")),
    AUTO_UPDATE_CHECKS("Automatic update checks", SettingsSectionKey.UPDATES, listOf("version", "notification")),
    CHECK_FOR_UPDATES("Check for updates", SettingsSectionKey.UPDATES, listOf("version", "release", "upgrade")),
}

internal object SettingsIndex {
    fun sectionsOf(page: SettingsPage): List<SettingsSectionKey> =
        SettingsSectionKey.entries.filter { it.page == page }

    /** Search over the entries this Android version shows. */
    fun searchFor(sdkInt: Int): SettingsSearch<SettingKey> = SettingsSearch(
        SettingKey.entries.filter { sdkInt >= it.minSdk }.map { key ->
            SearchDocument(key, key.title, key.keywords, listOf(key.section.title, key.section.page.title))
        },
    )
}
