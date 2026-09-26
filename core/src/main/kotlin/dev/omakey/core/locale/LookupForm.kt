package dev.omakey.core.locale

import java.text.Normalizer

/**
 * The form a word is looked up in: lowercase, Unicode NFC.
 *
 * Lowercase was always the rule. NFC is new with non-English vocabularies (AGENTS.md §66 Phase 3):
 * "é" can reach the keyboard as one code point (U+00E9) or as "e" + a combining acute (U+0301) — a
 * pasted word, a host app's own text, a hardware keyboard — and the two must find the same entry.
 * Devanagari has the same problem with nukta forms. The model is built in NFC, so input must be too.
 *
 * The typographic apostrophe (’) becomes ASCII ('), matching how models are built.
 *
 * Cheap for the common case: already-NFC text (all ASCII, and everything the keyboard itself types)
 * returns from the quick check without being copied.
 */
fun CharSequence.toLookupForm(): String {
    // The typographic apostrophe folds onto the ASCII one, as it does in the builder: "l’homme" and
    // "l'homme" are the same word. Same length, so positions still line up with the typed text.
    val lower = toString().lowercase().replace('\u2019', '\'')
    return if (Normalizer.isNormalized(lower, Normalizer.Form.NFC)) lower else Normalizer.normalize(lower, Normalizer.Form.NFC)
}
