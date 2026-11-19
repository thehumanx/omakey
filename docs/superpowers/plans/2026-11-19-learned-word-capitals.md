# Learned Words Keep Their Capitals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A learned word keeps the exact spelling the user saved ("iPhone", "Bibek") and the keyboard writes it that way.

**Architecture:** `words` gets a nullable `display` column next to the lowercase key. The spelling
travels through `PersonalLanguageModel.Entry` so ordinary typing never erases it, and is read
back through a new `PredictionEngine.spellingOf`. One pure `core` object, `WordCasing`, decides
the final capitals everywhere (strip, autocorrect, word finished), and `SavedSpelling` decides
what a swipe-up save stores.

**Tech Stack:** Kotlin, Room (KSP, exported schemas), Jetpack Compose, JUnit 4 (JVM tests in `core`).

**Spec:** `docs/superpowers/specs/2026-11-19-learned-word-capitals-design.md`

## Global Constraints
- Database 5 → 6 with `MIGRATION_5_6` appended to `MIGRATIONS`, `core/schemas/.../6.json` committed, never destructive migration (AGENTS.md invariant 3).
- `golden/en_us.txt` must pass unchanged; never regenerate it for this change.
- Lookups stay case-insensitive (`toLookupForm`); `word` stays the lowercase primary-key part.
- Saved spelling is stored exactly as written; a sentence-start capital the keyboard added itself is not kept.
- Mixed-case words keep their spelling at a sentence start ("iPhone", not "IPhone"); all caps typed (2+ letters) still gives all caps.
- Release is 5.8.1 (`versionCode` 52) together with the `suppressesSpellChecker` change. No APK build in this session (developer's instruction); compile + unit tests only.

## Review Focus
1. A learned word with a spelling, typed again casually → spelling survives (`persist` rewrites the row). Test in Task 2.
2. Settings edit changing only capitals ("bibek" → "Bibek") → saved, list shows "Bibek". Test in Task 1 (`normalise`) and Task 4 (DAO path is device-only).
3. Swipe-up on a dictionary word typed with inner capitals ("iPhone" while "iphone" is in the model) → spelling saved, not "already in the dictionary". Test in Task 3 (`SavedSpelling`) + Task 5 branch.
4. Word typed in all caps or at a sentence start → "BIBEK" / "Bibek", never "bIBEK". Test in Task 3.
5. Language without built-in capitalised words (Russian) → user spellings still applied (today's `cased` returns early). Test in Task 3.

---

### Task 1: Storage — `display` column, migration, edit rules

**Files:**
- Modify: `core/src/main/kotlin/dev/omakey/core/db/Entities.kt` (WordEntity)
- Modify: `core/src/main/kotlin/dev/omakey/core/db/OmakeyDatabase.kt` (version 6, `MIGRATION_5_6`)
- Modify: `core/src/main/kotlin/dev/omakey/core/db/Daos.kt` (`setDisplay`, `renameOrMerge`)
- Modify: `core/src/main/kotlin/dev/omakey/core/db/LearnedWordEdits.kt`
- Test: `core/src/test/kotlin/dev/omakey/core/db/LearnedWordEditsTest.kt`, `core/src/androidTest/.../OmakeyDatabaseMigrationTest.kt`

**Interfaces — Produces:**
- `WordEntity.display: String? = null` (last constructor parameter).
- `MIGRATION_5_6`: `ALTER TABLE words ADD COLUMN display TEXT` (no DEFAULT, so it matches Room's expected schema for a nullable column without `defaultValue`).
- `LearnedWordEdits.Edit(val word: String, val display: String?)`; `normalise(input, profile): Edit?` — `word` = lookup form, `display` = trimmed input when it contains an uppercase letter, else null.
- `LearnedWordEdits.merge(old, existing, newWord, display: String?): WordEntity` — result carries `display`.
- `WordDao.setDisplay(locale: String, word: String, display: String?)` (`UPDATE words SET display = :display WHERE locale = :locale AND word = :word`).
- `WordDao.renameOrMerge(locale, oldWord, newWord, display: String?)`.

- [ ] Tests (fail first): `normalise("Bibek")` → `Edit("bibek","Bibek")`; `normalise("bibek")` → `Edit("bibek", null)`; `normalise(" iPhone ")` → `Edit("iphone","iPhone")`; `normalise("Москва", Russian)` → `Edit("москва","Москва")`; `merge(old, null, "x", "X").display == "X"`; merge into existing keeps the edited `display`.
- [ ] Implement; run `:core:testDebugUnitTest --tests '*LearnedWordEditsTest'` → PASS.
- [ ] Add a v5 → 6 case to the migration test: a v5 row survives with all fields and `display IS NULL`. Device-only; not run in this session.
- [ ] `:core:compileDebugKotlin` regenerates `core/schemas/dev.omakey.core.db.OmakeyDatabase/6.json`; confirm the file exists and has the `display` column.

### Task 2: Spelling travels with the personal model

**Files:**
- Modify: `core/src/main/kotlin/dev/omakey/core/predict/PersonalLanguageModel.kt`
- Modify: `core/src/main/kotlin/dev/omakey/core/predict/PredictionEngine.kt`, `DeferredPredictionEngine.kt`, `NgramPredictionEngine.kt`
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/LocaleManager.kt` (`installPersonal` maps `display`)
- Test: `core/src/test/kotlin/dev/omakey/core/predict/PersonalLanguageModelTest.kt` (add), an `NgramPredictionEngine` test with a fake `WordDao` if one exists, else a `PersonalLanguageModel`-level test

**Interfaces — Produces:**
- `PersonalLanguageModel.Entry.display: String? = null`.
- `record(word: String, explicit: Boolean, display: String? = null): Entry?` — keeps the existing entry's `display` when `display` is null.
- `PersonalLanguageModel.spellingOf(word: String): String?`.
- `PredictionEngine.spellingOf(word: String): String? = null` (default); Deferred delegates; Ngram returns `personal.spellingOf(word)`.
- `NgramPredictionEngine.saveWord(word)` stores `display` = the clitic-stripped, trimmed `word` when it contains an uppercase letter; `persist` writes `entry.display` into `WordEntity`.

- [ ] Tests (fail first): record("iphone", explicit=true, display="iPhone") then record("iphone", explicit=false) → `spellingOf("IPHONE") == "iPhone"`; load with an entry carrying display → `spellingOf` returns it; record with no display on a new word → `spellingOf` null.
- [ ] Implement; run `:core:testDebugUnitTest --tests '*PersonalLanguageModel*'` → PASS.

### Task 3: Casing rules in `core`

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/predict/WordCasing.kt`
- Create: `core/src/main/kotlin/dev/omakey/core/input/SavedSpelling.kt`
- Modify: `core/src/main/kotlin/dev/omakey/core/predict/SuggestionComposer.kt` (`cased`, `alternatives`)
- Test: `core/src/test/kotlin/dev/omakey/core/predict/WordCasingTest.kt`, `core/src/test/kotlin/dev/omakey/core/input/SavedSpellingTest.kt`, existing `SuggestionComposerTest`

**Interfaces — Produces:**
- `WordCasing.fixedSpelling(word: String, profile: LanguageProfile, userSpelling: (String) -> String?): String?` — user spelling of the word (or of its part after a clitic, clitic prefix kept as typed) first, else `profile.alwaysCapitalized`, else null.
- `WordCasing.forTyped(typed: String, chosen: String, profile, userSpelling): String` — typed is 2+ letters all uppercase → `chosen.uppercase()`; else fixed spelling of `chosen` if any; else `matchCase(typed, chosen)`.
- `WordCasing.forSuggestion(word: String, profile, userSpelling): String` — fixed spelling, else `AutoCapitalization.capitalizedSuggestion(word, profile)`.
- `SavedSpelling.of(word: String, textBeforeCursor: String, profile, mode: AutoCapitalization.FieldMode, autoCapitalizeEnabled: Boolean): String` — returns `word` unchanged unless: auto-capitalise is on, only the first letter is uppercase, the word is found in `textBeforeCursor` (last occurrence), and `AutoCapitalization.shouldCapitalize(text before that occurrence, profile, mode)`; then the first letter is lowercased.
- `SuggestionComposer(..., spellingOf: (String) -> String? = { null })` — `cased` no longer returns early when `alwaysCapitalized` is empty.

- [ ] Tests (fail first), `WordCasingTest` with userSpelling `{"bibek"→"Bibek","iphone"→"iPhone"}`: forTyped("bibek","bibek")="Bibek"; ("Bibek","bibek")="Bibek"; ("BIBEK","bibek")="BIBEK"; ("Iphone","iphone")="iPhone"; ("hello","hello")="hello"; ("Hello","hello")="Hello"; user spelling beats built-in ("i"→ user "i" absent → "I"); forSuggestion under `LanguageProfile` with empty `alwaysCapitalized` still applies "Bibek".
- [ ] Tests, `SavedSpellingTest`: ("Hello", "Hello", sentences) → "hello"; ("Hello", "I said Hello", …) → "Hello"; ("iPhone", "iPhone", …) → "iPhone"; ("Bibek", "Bibek", autoCapitalizeEnabled=false) → "Bibek"; word not found in text → unchanged.
- [ ] Implement; run `:core:testDebugUnitTest` (whole module, golden included) → PASS, golden unchanged.

### Task 4: Settings → Learned words

**Files:** Modify `app/src/main/kotlin/dev/omakey/app/settings/SettingsOverlays.kt`

**Interfaces — Consumes:** `LearnedWordEdits.normalise/Edit`, `WordDao.setDisplay`, `WordDao.renameOrMerge(…, display)`.

- [ ] List row text: `entry.display ?: entry.word`. Dialog starts with that text.
- [ ] Save enabled when `normalise` is non-null and (`edit.word != entry.word` or `edit.display != entry.display`). Same key → `setDisplay`; new key → `renameOrMerge(locale, entry.word, edit.word, edit.display)`. Then `signal.bump()` and `reload()` as today.
- [ ] `:app:compileDebugKotlin` → BUILD SUCCESSFUL. Device check listed in the spec.

### Task 5: Keyboard wiring

**Files:** Modify `app/src/main/kotlin/dev/omakey/app/keyboard/KeyboardViewModel.kt`

**Interfaces — Consumes:** `PredictionEngine.spellingOf`, `WordCasing`, `SavedSpelling`.

- [ ] `SuggestionComposer(…, spellingOf = predictionEngine::spellingOf)`.
- [ ] Word finished (the `alwaysCapitalized` step): `WordCasing.fixedSpelling(typed, profile, predictionEngine::spellingOf)` when typed is not all caps (2+ letters) and differs from it → commit it as a correction (same gating: auto-capitalise or autocorrect on, separator not "."). After autocorrect: `WordCasing.forTyped(typed, correctedLower, …)` replaces `matchCase`.
- [ ] Swipe-up save: `spelling = SavedSpelling.of(original, textEditor.textBeforeCursor(AUTO_CAP_CONTEXT_CHARS + original.length + 2), profile, fieldCapsMode, settings.autoCapitalizeEnabled)`. Save branch passes `spelling` to `saveWord`. The "already in the dictionary" branch: when `spelling` has an uppercase letter, save it instead (banner "$spelling learned"), so "iPhone" can be saved over the dictionary's "iphone".
- [ ] `:app:compileDebugKotlin` → BUILD SUCCESSFUL.

### Task 6: Verify and document
- [ ] `./gradlew :core:compileDebugKotlin :app:compileDebugKotlin :extensions-builtin:compileDebugKotlin` and `:core:testDebugUnitTest :app:testDebugUnitTest` (JDK 17) → all pass.
- [ ] CHANGELOG Unreleased: "Learned words keep your spelling" entry, thanking the reporter (credit rule: name only the confirmed reporter).
- [ ] `docs/internal/decisions.md` §86: `display` column, the persist trap, the sentence-start rule.
- [ ] No APK build, no commit until the developer asks.
