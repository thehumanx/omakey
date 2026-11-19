# Learned words keep their capitals — design

Date: 2026-11-19. Status: awaiting the developer's approval.

## Intent

A beta tester reported that learned words can't be edited or capitalised. 5.7.0 fixed one cause
(renaming onto an existing word crashed), but the case of capitals was never possible. Learned
words are stored and looked up lowercase (`toLookupForm`), so the edit dialog normalises "Bibek"
to "bibek", sees no change, and Save silently does nothing. Even a stored capital would be
ignored, because suggestions and autocorrect take their capitals only from what was typed
(`matchCase`). Reproduced by the developer on 5.8.0: save "bibek", edit to "Bibek", still "bibek".

**Finish line:** a user can give a learned word its own spelling ("Bibek", "iPhone", "McDonald",
"Москва") in Settings, and the keyboard then writes it that way whenever it suggests, completes
or corrects to that word, in every language. Existing learned words are kept through the
upgrade.

## Goals
1. **A saved word is stored exactly as the user wrote it.** Saving "iPhone" stores "iPhone": not
   "iphone", not "Iphone". This holds for both ways of saving: the edit dialog in Settings and
   the swipe-up save while typing. The edit dialog saves a change that only differs in capitals,
   and the list shows the word as the user spelled it.
2. Suggestions, completions and autocorrect use the stored spelling for that word.
3. Typing the word in plain lowercase and finishing it (space or punctuation) writes the stored
   spelling, the same way "i" already becomes "I" (`AutoCapitalization.alwaysCapitalized`), and
   under the same settings (auto-capitalise or autocorrect on).
4. Typing it in all caps still gives all caps ("BIBEK").
5. A database upgrade (v5 → v6) that keeps every learned word, with a migration test.

## Non-goals
- **Learning capitals from ordinary typing.** Whether "Bibek" typed mid-sentence is a name or a
  stray Shift is guesswork. Only an explicit save (Settings edit or swipe-up) sets a spelling;
  words picked up from typing stay without one.
- Capitals for words from the built-in word lists that the user never learned ("london").
- A spelling per language variant beyond what the table already keys on (`locale`, `word`).
- Changing the language-model binary format or any pack.

## Anti-goals (a fix that does these has failed)
- Losing or resetting any learned word, its count, its saved/explicit flag or its language on
  upgrade.
- A stored spelling silently dropped the next time the word is typed (see "Trap" below).
- Matching becoming case-sensitive: "bibek", "Bibek" and "BIBEK" must all still find the word.
- Any change to English behaviour without learned words: `golden/en_us.txt` must pass
  unchanged.

## Design

### Storage
- `WordEntity` gains `display: String? = null`: the word as the user wants it written, or null
  for "no fixed spelling; follow what was typed". `word` stays the lowercase lookup key and the
  primary key (`locale`, `word`) is unchanged.
- Only stored when it differs from `word`; an edit back to all-lowercase clears it to null.
- `display` must have the same lookup form as `word` (`display.toLookupForm() == word`). Anything
  else is a rename, not a re-spelling.

### Migration
- `OmakeyDatabase` version 5 → 6, `MIGRATION_5_6`:
  `ALTER TABLE words ADD COLUMN display TEXT DEFAULT NULL`. Appended to `MIGRATIONS`, exported
  `core/schemas/.../6.json` committed, and a 5 → 6 case in `OmakeyDatabaseMigrationTest` (needs a
  device or emulator: `./gradlew :core:connectedDebugAndroidTest`).

### Trap: ordinary typing rewrites the row
`NgramPredictionEngine.persist` upserts a fresh `WordEntity` every time a learned word is used.
Without care, that writes `display = null` and erases the user's spelling the first time they type
the word. The spelling must travel with the in-memory entry: `PersonalLanguageModel.Entry` gains
`display`, `load` fills it from the database, `record` keeps it, and `persist` writes it back. A
test types a word with a stored spelling and checks the spelling survives.

`LearnedWordEdits.merge` follows the same rule: on a rename or merge, the edited spelling wins.

### Edit dialog (Settings → Learned words)
- `LearnedWordEdits.normalise` returns both the lookup key and the spelling to store (null when the
  input was all lowercase).
- Save is enabled when the key or the spelling changed. A key change renames or merges as today;
  a spelling-only change updates `display` in place (new `WordDao.setDisplay`). Either way,
  `LearnedWordsSignal` is bumped so the running keyboard reloads.
- The list shows `display ?: word`.

### Swipe-up save
`saveWord` stores the word exactly as it appears in the text: "iPhone" → `display = "iPhone"`,
"bibek" → no display. (Swipe-up on an already-learned word unlearns it, as today; a spelling is
changed in Settings.) A word in the built-in dictionary typed with capitals ("iPhone" where the
model has "iphone") is saved with that spelling instead of "already in the dictionary".

One exception, decided here: a capital the **keyboard** added on its own (auto-capitalise at the
start of a sentence) is not the user's spelling, so "Hello" saved at a sentence start is stored as
"hello". Judged by position, not by tracking Shift per keystroke (swipe typing and corrections
make that unreliable): when only the first letter is a capital, auto-capitalise is on, and the
word sits where auto-capitalise would have put a capital, the capital is dropped. A word with
inner capitals ("iPhone", "McDonald") is always kept as written. Cost: a name deliberately
capitalised at a sentence start saves lowercase and needs a Settings edit.

### Typing
One lookup, used everywhere a word's capitals are decided: "fixed spelling of this word in the
active language". It combines the language's built-in fixed words (`alwaysCapitalized`: "I",
"I'm") with the user's learned spellings, the user's winning on a clash.
- **Suggestions and completions:** `SuggestionComposer.cased` applies it to every word it
  returns, not only when `alwaysCapitalized` is non-empty (today it returns early for languages
  without such words, which would skip Russian).
- **Autocorrect and alternatives:** after the correction is chosen, all caps typed → all caps;
  otherwise a fixed spelling wins; otherwise `matchCase` as today.
- **Finishing a typed word:** the existing `alwaysCapitalized` step in `KeyboardViewModel`
  consults the combined lookup, so "bibek␣" becomes "Bibek␣" and is undoable like any
  correction.
- Apostrophe clitics keep working: "l'homme" style splitting is unchanged, and only the word part
  takes the fixed spelling.

The rules live in `core` (a small `WordCasing` with tests), not in `KeyboardViewModel`.

## Testing
- `core` unit tests: normalise (spelling-only edit, back to lowercase, mixed case, Cyrillic),
  merge keeps the edited spelling, `persist` keeps the spelling across uses, the casing rule
  (lowercase / first-capital / all-caps typed × with / without a fixed spelling, user spelling
  beats built-in), `cased` on a language without built-in fixed words.
- Migration test 5 → 6 (device or emulator).
- `GoldenBehaviourTest` unchanged.
- **Not provable by tests:** the Settings dialog and typing on a phone. Device checks: save
  "bibek", edit to "Bibek", type "bibek " in a text box → "Bibek "; type "BIBEK" → "BIBEK";
  type it a few more times and confirm Settings still shows "Bibek"; upgrade over 5.8.0 with
  learned words present and confirm none are lost.

## Open questions for the developer
1. ~~Swipe-up save keeps the typed spelling?~~ **Decided (developer, 2026-11-19): yes, saved
   exactly as written** ("iPhone" stays "iPhone"), and a capital the keyboard added by itself at
   a sentence start is ignored (developer confirmed).
2. **Sentence start for a mixed-case word:** "iPhone" at the start of a sentence stays "iPhone"
   (what most keyboards do), not "IPhone". OK?
3. ~~Release?~~ **Decided (developer, 2026-11-19): 5.8.1**, together with the spell-checker
   change (`suppressesSpellChecker`); a small release, not 5.9.0.
