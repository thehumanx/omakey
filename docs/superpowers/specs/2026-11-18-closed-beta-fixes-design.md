# Closed-beta fixes — design (5.7.0)

Date: 2026-11-18. Status: awaiting the developer's approval.

## Intent

omakey is in Play closed testing. Testers reported autocorrect gaps, a missing `%`, red
spellcheck underlines on Russian text, and learned words that can't be edited; Play Console flags
0 % obfuscation and edge-to-edge. The developer also wants a modern, readable Settings screen,
custom themes in the theme tile, calmer auto-derived grid colours, and a configurable key next to
the spacebar. Finish line: one release, 5.7.0, shipped as a signed GitHub APK (sideload flavor)
and a Play bundle for closed testing, with CHANGELOG/README updated and a first-time Play guide.

## Goals
1. Autocorrect: fix the contraction and shorthand miscorrections; add Russian orthographic-error
   measurement; tune against both (approach A).
2. Every common ASCII symbol reachable from the symbols grids, `%` first; a test that keeps it so.
3. Declare an input-method subtype per enabled language and keep the system's current subtype in
   step with the active language, so the system spellchecker checks the right language.
4. Learned-word edit works and the running keyboard sees edits and deletions.
5. The theme tile cycles presets *and* custom themes.
6. Auto-derived grid colours (border especially) look calm, not harsh.
7. A setting for the key right of the spacebar: "." by default, any single character allowed.
8. Clear both Play Console items: R8 obfuscation on release builds; edge-to-edge handled.
9. Settings redesigned: readable copy, real spacing between text and switches, modern grouping.
10. Release 5.7.0 (both flavors) + a Play closed-testing guide.

## Non-goals
- Bigger training corpora (B) and an on-device neural model (C). Dropped by the developer.
- An omakey spellchecker service (`SpellCheckerService`). Android lists spellcheckers separately
  from keyboards; omakey doesn't provide one, which is why none can be selected. Out of scope.
- Changing the language-model binary format or rebuilding any pack.

## Anti-goals (a fix that does these has failed)
- Autocorrect that silently rewrites real words ("well" → "we'll", "its" → "it's").
- Any Room schema change (none is needed; if one turns out to be, stop and ask).
- A release build that crashes or loses settings because R8 stripped something.
- Settings copy with class names or jargon.

## Constraints
AGENTS.md invariants hold throughout; in particular: only `TextEditor` touches `InputConnection`;
`ignoreUnknownKeys` stays on every stored-data `Json`; English golden changes only on purpose and
the commit says why; nothing is pushed, tagged, signed or installed without the developer saying
so at that moment. Touch/IME/layout behaviour is not proven by tests: each such item lists what to
try on the phone.

## Approaches considered (autocorrect quality)
- **A. Rules + measurement + tuning** — picked. Cheap, measurable, no size change.
- B. Larger corpora for context n-grams — dropped by the developer.
- C. On-device neural LM (~2–5 MB per language) — dropped by the developer.

Every other item has one sensible way; each is below.

## Design per item

### 1. Autocorrect (approach A)
Measured today on the bundled English model: `dont`→font, `youre`→your, `theyre`→there,
`ive`→ice, `thats`→that, `pls`→old, `thx`→the, `im` left alone. Cause: `AutocorrectIndex.correct`
never consults the contraction table; only `alternatives` (the strip) does.

- **Contractions on space.** In `correct`, before the edit-distance search: if the typed word has a
  contraction (`contractionFor`) and either the typed word is *not* a known word, or the language
  model rates the contraction far more likely than the bare word (unigram ratio above a tuned
  threshold, so `cant`/`wont` qualify while `well`/`its`/`were`/`ill` don't), return the
  contraction. Data-driven, not a hand list of exceptions. `id` falls whichever way the ratio
  says; it stays offered in the strip either way.
- **Shorthand is left alone.** A per-language `keepAsTyped` set (new optional `ProfileSpec` field,
  default empty; English gets pls, plz, thx, thnx, ty, np, idk, imo, btw, lol, omg, brb, ttyl,
  tbh, smh, ikr, fyi, asap, gonna, wanna, gotta, ok, k, u, ur). `correct` never rewrites these;
  they still get strip alternatives.
- **Russian measurement.** A synthetic orthographic-error generator in the eval suite, applied to
  held-out Russian sentences: и↔й, е↔ё, ь/ъ dropped or swapped, doubled consonant collapsed,
  unstressed о↔а and е↔и, -тся↔-ться. Reported as fixed / wrong / damaged like the other
  measures. Synthetic, and said so in the test doc.
- **Tuning.** Re-run `EngineEvaluationTest`, `LanguagePackEvaluationTest` (needs a built `ru_RU`
  pack locally: `scripts/build_pack.py`, output stays in `build/packs/`, not committed), and the
  tuning sweep; report fixed *and* wrongly-changed before/after. Floors move only upward.
- **Golden.** `en_us.txt` will change (contractions now correct on space). Regenerate on purpose;
  the commit says so.
- **Tester's swipe-down report.** Code path is correct on the developer's model. Likeliest causes:
  a non-English language active (contractions are English-only), or expecting the space-bar
  behaviour Gboard has (fixed by the above). Recorded in the changelog wording, not guessed at.

### 2. Symbols
- `%` replaces `¶` in `Symbols2` row 1 (Gboard puts `%` on its second page too); `¶` becomes a
  long-press option of `•`.
- `SymbolsPageTest` stops counting the top-strip extra row (it can be hidden), and asserts every
  printable ASCII symbol is on `Symbols1 ∪ Symbols2` for both the Fleksy and classic first page.

### 3. Language subtypes
- At IME start and whenever the enabled-language list changes, register one additional subtype
  per enabled language (`setAdditionalInputMethodSubtypes`), with the language's tag, a stable
  `subtypeId` derived from its id, `isAsciiCapable` from its script, and a label like
  "Русский" / "Español". English keeps the static XML subtype.
- When the active language changes, switch the system's current subtype to match
  (`switchInputMethod(id, subtype)`). When the user picks a subtype from the system switcher
  (`onCurrentInputMethodSubtypeChanged`), switch omakey's language to it. Guard against the loop.
- Risk: some Android builds only honour *enabled* subtypes. If the switch doesn't take on the
  phone, fall back to marking the additional subtypes implicitly enabled. Device-test item.

### 4. Learned words
- `rename` becomes one transaction: validate the new word (word characters of that language,
  stored via the lookup form the keyboard itself uses), and if the target already exists, merge
  (sum counts, keep `explicit`, latest timestamp) and delete the old row instead of hitting the
  primary-key conflict that currently crashes the screen.
- The running keyboard is told: Settings bumps a "learned words changed" counter through the same
  cross-process SharedPreferences listener pattern the other preference classes use; the IME
  reloads the active language's personal model on change. Fixes rename *and* the existing
  "deleted word keeps being protected until restart".
- No schema change.

### 5. Theme tile
`cycleTheme` cycles presets, then custom themes in Settings order, then wraps. The banner shows
the theme name.

### 6. Grid colours (custom-theme derivation)
`ThemeDerivation.fromBackground`'s grid border moves from "75 % toward white/black, at least 3:1"
to a soft line: mixed toward the label colour until it reaches a contrast band of roughly
1.5–2.0:1 against the background (chosen from the HTML mock: **Soft, 1.6–2.0:1**; settings rows: **style A, grouped cards**; copy approved as written — developer, 2026-11-18). Keys get a touch more
separation from the background where they're near-identical. Built-in presets untouched. Existing
custom themes that never overrode the border pick up the new value (they follow the background).
Unit tests pin the band.

### 7. Key beside the spacebar
- New `LayoutPreferences` field `spacebarSideKey: String = "."`.
- Applied at display time to the active letter layout: the character key immediately right of
  SPACE in the bottom row (whatever it types — "." in most languages, "।" in Nepali) gets the
  chosen label/text; its long-press list keeps its options with the replaced character added first
  and the chosen character removed, so nothing becomes unreachable. Works for every bundled and
  pack layout without pack changes. Symbols pages keep their own ".".
- Settings: a row "Key next to the spacebar" with quick choices (. , ? ! ' -) and a "Custom…" field
  accepting exactly one character (one grapheme, emoji allowed).
- Double-tap-space still inserts the language's period.

### 8. Play Console
- **Obfuscation:** `isMinifyEnabled = true`, `isShrinkResources = true` on release, with
  `proguard-rules.pro` keeping what is reached by name (serializable classes, Room entities/DAOs
  if their consumer rules fall short, the IME service and settings activity via the manifest,
  extension entry points). Verified by running the release build on the phone. The Play bundle
  carries the mapping file automatically.
- **Edge-to-edge:** `enableEdgeToEdge()` in `SettingsActivity` with insets applied to every
  screen and overlay; confirm the keyboard window itself pads for the navigation bar on
  Android 15+.

### 9. Settings redesign
- First an HTML mock (throwaway) of two screens — a toggle-heavy section (Suggestion bar /
  Buttons) and Gestures — in the new style, shown to the developer for approval.
- Direction for the mock: one card per group with 16 dp inner padding; rows with 16 dp gap between
  text and control and 12–16 dp vertical padding; dividers between rows; titles in the body style,
  descriptions shorter and in a muted colour; descriptions rewritten for a first-time user (one
  plain sentence where possible, no "Off by default", no cross-references to other settings).
- Then the real Compose change: shared row components (`UnindexedSettingToggle`,
  `UnindexedClickableSettingRow`, `SettingsSection`) carry the spacing, so every screen picks it
  up; every description rewritten. Search keywords keep working.

### 10. Release
- `versionCode` 49+, `versionName` 5.7.0. CHANGELOG entry; README for user-visible changes.
- Sideload: `:app:assembleSideloadRelease` (signed with the existing keystore).
- Play: `:app:bundlePlayRelease`.
- GitHub release + tag only after the developer says go at that point.
- `docs/play-closed-testing.md`: step-by-step for uploading the bundle to the closed track,
  release notes, testers, review.

## Left to the builder
Exact ratio threshold and contrast band (from measurement and the mock); internal naming; test
layout; order of commits.

## Calls I made (check these)
- The ratio rule rather than a hand list for which contractions apply on space.
- `%` replaces `¶` on symbols page 2.
- Synthetic Russian error set rather than an external dataset (no licence question, but synthetic).
- The keyboard learns about Settings edits through a preference counter, not a database listener.
- No spellchecker service in this release.
