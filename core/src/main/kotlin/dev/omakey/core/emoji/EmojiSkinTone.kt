package dev.omakey.core.emoji

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The five Fitzpatrick skin-tone modifiers, plus the default yellow.
 *
 * Unicode defines exactly these five (`U+1F3FB`–`U+1F3FF`) as `EMOJI_MODIFIER` characters; a
 * supporting emoji is followed by one to select a tone. The yellow default is the *absence* of a
 * modifier, not a sixth character — which is why [modifier] is null there rather than some
 * placeholder, and why [apply] has a trivially correct no-op path.
 *
 * [sample] is a fixed emoji used to draw the choice in Settings. It is the raised-hand ("✋"), the
 * same one the OS emoji picker uses for this, because it is unambiguous at swatch size and its tone
 * covers most of the glyph — a face would show mostly features, not skin.
 */
enum class EmojiSkinTone(val id: String, val label: String, val modifier: String?) {
    DEFAULT("default", "Default", null),
    LIGHT("light", "Light", "🏻"),
    MEDIUM_LIGHT("medium_light", "Medium light", "🏼"),
    MEDIUM("medium", "Medium", "🏽"),
    MEDIUM_DARK("medium_dark", "Medium dark", "🏾"),
    DARK("dark", "Dark", "🏿");

    /** This tone applied to [SAMPLE_BASE], for the Settings swatches. */
    val sample: String get() = apply(SAMPLE_BASE)

    /**
     * [emoji] rendered in this tone, or unchanged if it doesn't support tone selection.
     *
     * Any modifier already present is stripped first, so re-toning an emoji that came from recents
     * (already carrying whatever tone was in force when it was used) replaces rather than appends —
     * appending would produce a sequence no font has a glyph for, which renders as two boxes.
     */
    fun apply(emoji: String): String {
        val base = stripModifiers(emoji)
        if (modifier == null || !supportsSkinTone(base)) return base
        // The modifier attaches to the first code point. For a ZWJ sequence ("👍" is one code point
        // but "🧑‍🤝‍🧑" is several joined) only that first one takes it — handling every multi-person
        // sequence properly needs per-emoji data this doesn't have, so those are left alone by
        // supportsSkinTone below rather than mangled.
        return base + modifier
    }

    companion object {
        private const val SAMPLE_BASE = "✋" // raised hand

        val ALL: List<EmojiSkinTone> = entries

        fun fromId(id: String?): EmojiSkinTone = entries.firstOrNull { it.id == id } ?: DEFAULT

        private val MODIFIERS = entries.mapNotNull { it.modifier }

        fun stripModifiers(emoji: String): String =
            MODIFIERS.fold(emoji) { text, modifier -> text.replace(modifier, "") }

        /**
         * Whether [emoji] is one of the emoji Unicode allows a tone modifier on.
         *
         * Deliberately a conservative code-point range check over the single-code-point emoji that
         * carry `Emoji_Modifier_Base`, not the full property table: the cost of a false positive is
         * a tofu box in the user's message, while the cost of a false negative is one emoji staying
         * yellow. Multi-code-point ZWJ sequences are excluded outright — toning those correctly
         * means applying the modifier to each person in the sequence, which needs real emoji data.
         */
        fun supportsSkinTone(emoji: String): Boolean {
            val stripped = stripModifiers(emoji)
            if (stripped.isEmpty()) return false
            if (stripped.codePointCount(0, stripped.length) != 1) return false
            val codePoint = stripped.codePointAt(0)
            return MODIFIER_BASE_RANGES.any { codePoint in it }
        }

        /** Code-point ranges of single-character `Emoji_Modifier_Base` emoji — hands, body parts,
         * people and the gesture/activity emoji that depict them. */
        private val MODIFIER_BASE_RANGES = listOf(
            0x261D..0x261D, // index pointing up
            0x26F9..0x26F9, // person bouncing ball
            0x270A..0x270D, // raised fist through writing hand
            0x1F385..0x1F385, // Santa
            0x1F3C2..0x1F3C4, // snowboarder, golfer, surfer
            0x1F3C7..0x1F3C7, // horse racing
            0x1F3CA..0x1F3CC, // swimmer, weight lifter, golfer
            0x1F442..0x1F443, // ear, nose
            0x1F446..0x1F450, // pointing hands through open hands
            0x1F466..0x1F478, // people
            0x1F47C..0x1F47C, // baby angel
            0x1F481..0x1F483, // tipping hand, guard, dancer
            0x1F485..0x1F487, // nail polish, massage, haircut
            0x1F48F..0x1F48F, // kiss
            0x1F491..0x1F491, // couple with heart
            0x1F4AA..0x1F4AA, // flexed biceps
            0x1F574..0x1F575, // person in suit, detective
            0x1F57A..0x1F57A, // man dancing
            0x1F590..0x1F590, // hand with fingers splayed
            0x1F595..0x1F596, // middle finger, vulcan salute
            0x1F645..0x1F647, // gesturing no/ok, bowing
            0x1F64B..0x1F64F, // raising hand through folded hands
            0x1F6A3..0x1F6A3, // rowboat
            0x1F6B4..0x1F6B6, // biking, walking
            0x1F6C0..0x1F6C0, // person taking bath
            0x1F6CC..0x1F6CC, // person in bed
            0x1F90C..0x1F90C, // pinched fingers
            0x1F90F..0x1F90F, // pinching hand
            0x1F918..0x1F91F, // horns through love-you gesture
            0x1F926..0x1F926, // face palm
            0x1F930..0x1F939, // pregnant through juggling
            0x1F93D..0x1F93E, // water polo, handball
            0x1F977..0x1F977, // ninja
            0x1F9B5..0x1F9B6, // leg, foot
            0x1F9B8..0x1F9B9, // superhero, supervillain
            0x1F9BB..0x1F9BB, // ear with hearing aid
            0x1F9CD..0x1F9CF, // standing, kneeling, deaf person
            0x1F9D1..0x1F9DD, // people and fantasy beings
            0x1FAC3..0x1FAC5, // pregnant man/person, person with crown
            0x1FAF0..0x1FAF8, // newer hand gestures
        )
    }
}

/** Persists the chosen skin tone. Same SharedPreferences + cross-instance-sync pattern as the other
 * `*Preferences` classes — the Settings Activity and the IME service each hold their own instance,
 * and the registered listener is what keeps an already-open keyboard in sync with a change made
 * from Settings. */
class EmojiSkinTonePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _skinTone = MutableStateFlow(EmojiSkinTone.fromId(prefs.getString(KEY_SKIN_TONE, null)))
    val skinTone: StateFlow<EmojiSkinTone> = _skinTone

    private val prefsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _skinTone.value = EmojiSkinTone.fromId(prefs.getString(KEY_SKIN_TONE, null))
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsChangeListener)
    }

    fun setSkinTone(tone: EmojiSkinTone) {
        prefs.edit().putString(KEY_SKIN_TONE, tone.id).apply()
        _skinTone.value = tone
    }

    private companion object {
        const val PREFS_NAME = "omakey_emoji_prefs"
        const val KEY_SKIN_TONE = "skin_tone"
    }
}
