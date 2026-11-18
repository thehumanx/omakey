# Closed-beta fixes (5.7.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the closed-beta reports (autocorrect, `%`, spellcheck underlines, learned-word edit), add the requested features (theme tile, calmer grid colours, spacebar-side key, Settings redesign), clear the Play Console warnings, and ship 5.7.0 for GitHub and Play.

**Architecture:** Logic lands in `core` with JVM tests (pure functions where the app layer can't be tested); `app` wires it. No Room schema change, no LM format change, no pack rebuild for release.

**Tech Stack:** Kotlin, Jetpack Compose, Room, kotlinx.serialization, JUnit4, Gradle (JDK 17).

**Spec:** `docs/superpowers/specs/2026-11-18-closed-beta-fixes-design.md`

## Global Constraints
- `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` before every Gradle command.
- AGENTS.md invariants 1–10 hold; in particular: no Room schema change; `ignoreUnknownKeys = true` on every stored-data `Json`; golden `en_us.txt` regenerated only on purpose, commit says why.
- Autocorrect changes report **fixed and wrongly-changed** numbers before/after (core/AGENTS.md).
- Settings copy: plain words, no class names, no "Off by default", no cross-references.
- Nothing pushed, tagged, signed-and-published, or installed without the developer saying so at that moment.
- Touch/IME/layout items end with "not device-tested" plus what to try on the phone.

## Review Focus
1. **Capitalised contraction at sentence start** ("Dont" → "Don't", "Im" → "I'm"): case must survive the new on-space path. Test in Task 1.
2. **Spacebar-side key on Nepali** (key is "।", not "."): the replaced character must stay reachable via long-press. Test in Task 4.
3. **Rename to an existing learned word / to an invalid word** ("hello world", "", digits): no crash, no corrupt row. Test in Task 6.
4. **Theme tile with zero custom themes, or with the current theme deleted**: cycle still works and wraps. Test in Task 5.
5. **R8 release build loading stored themes/layouts/packs from older installs**: must deserialize. Covered by Task 8's device checklist (no JVM test can prove R8 output).

---

### Task 1: Contractions on space, shorthand left alone (English)

**Files:**
- Modify: `core/src/main/kotlin/dev/omakey/core/predict/AutocorrectIndex.kt` (`correct`)
- Modify: `core/src/main/kotlin/dev/omakey/core/locale/LanguageProfile.kt` (new field), `core/src/main/kotlin/dev/omakey/core/pack/ProfileSpec.kt` (new optional field)
- Create: `core/src/main/kotlin/dev/omakey/core/locale/EnglishShorthand.kt`
- Test: `core/src/test/kotlin/dev/omakey/core/predict/ContractionAutocorrectTest.kt`
- Regenerate: `core/src/test/resources/golden/en_us.txt`

**Interfaces:**
- Produces: `LanguageProfile.keepAsTyped: Set<String>` (lookup form, default empty); `ProfileSpec.keepAsTyped: List<String> = emptyList()`; `AutocorrectIndex` constructor param `contractionRatio: Float = CONTRACTION_RATIO` (log10 ratio).

- [ ] **Step 1: Write failing tests** (use `TestLanguageModel.load()` like `GoldenBehaviourTest`)
  - `unknown bare forms correct to the contraction`: `correct(w)` for dont, im, ive, youre, theyre, thats, didnt → don't, i'm, i've, you're, they're, that's, didn't (lowercase; `AutoCapitalization` / `matchCase` handle casing at commit).
  - `real words stay`: `correct(w)` is null for well, its, were, ill, hell, shed, wed.
  - `shorthand stays`: `correct(w)` is null for pls, thx, idk, btw, lol, u, ur.
  - `shorthand still gets alternatives`: `alternatives("pls", 3)` is non-empty.
  - `capitalised input`: `correct("Dont")` is "don't" (case restored by caller; assert caller-side via `matchCase` if public, else assert lookup works on capitalised input).
- [ ] **Step 2: Run, verify they fail.** `./gradlew :core:testDebugUnitTest --tests '*ContractionAutocorrectTest'` → FAIL (dont→font etc.).
- [ ] **Step 3: Implement.** In `correct`, after the known-word / keepAsTyped early return logic:
  - If lookup form ∈ `profile.keepAsTyped` → return null.
  - `contractionFor(lower)` exact-or-fuzzy hit `c`: return `c.lowercase()`-as-stored if `!isKnown(lower)`, or if `logP(c) - logP(lower) >= contractionRatio` (unigram log10 probs from the model; a contraction absent from the model never qualifies via the ratio).
  - Then the existing path.
  - Start `CONTRACTION_RATIO = 1.0f` (10×); the tuning step below sets the final value.
  - English `keepAsTyped` in `EnglishShorthand.kt`: pls plz thx thnx ty np idk imo imho btw lol omg brb ttyl tbh smh ikr fyi asap gonna wanna gotta ok k u ur ya yall.
- [ ] **Step 4: Run tests → PASS.**
- [ ] **Step 5: Measure.** Run `EngineEvaluationTest` and `EngineTuningTest` before (stash) and after; record fixed / wrong / damaged. Sweep `contractionRatio` over {0.5, 1.0, 1.5, 2.0}; pick the value with no rise in damaged words. Note numbers in the test's KDoc.
- [ ] **Step 6: Regenerate golden** `-Domakey.golden.update=true --tests '*GoldenBehaviourTest'`; diff must show only contraction/shorthand lines.
- [ ] **Step 7: Commit** — message names the golden change and why.

### Task 2: Russian orthographic-error measurement

**Files:**
- Create: `core/src/test/kotlin/dev/omakey/core/predict/eval/RussianOrthography.kt` (generator)
- Modify: `core/src/test/kotlin/dev/omakey/core/predict/eval/LanguagePackEvaluationTest.kt` (new case)
- Test: `core/src/test/kotlin/dev/omakey/core/predict/eval/RussianOrthographyTest.kt`

**Interfaces:**
- Produces: `object RussianOrthography { fun misspell(word: String, random: Random): String? }` — returns one misspelling or null when no rule applies.

- [ ] **Step 1: Failing generator tests**: deterministic seed; rules each produce the expected form: "мой"→"мои" (й→и), "ещё"→"еще" is *not* produced (е/ё is an equivalence, not an error), "объявление"→"обявление" (ъ dropped), "класс"→"клас", "молоко"→"малоко", "учится"→"учиться" and vice versa.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `misspell`: pick uniformly among applicable rules: й↔и, ь/ъ dropped, ь↔ъ, doubled consonant collapsed, unstressed о→а / е→и (any non-first occurrence; synthetic, no stress data), -тся↔-ться. Skip words under 4 letters.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Evaluation case** `russian orthography`: held-out ru sentences, words misspelled with seed 66, scored fixed / wrong like the accent case; floor set just under the measured value. Build the pack locally first: `scripts/build_pack.py ru_RU` (output stays in `build/packs/`, not committed). If the pack can't be built offline, the case `assumeTrue`-skips like the others and the numbers are recorded as "not measured".
- [ ] **Step 6: Tune** only if Task 1's changes moved Russian (they shouldn't — Russian has no contractions); record numbers in the KDoc.
- [ ] **Step 7: Commit.**

### Task 3: `%` on the symbols grid

**Files:**
- Modify: `core/src/main/kotlin/dev/omakey/core/layout/Layouts.kt` (`Symbols2` row 1)
- Test: `core/src/test/kotlin/dev/omakey/core/layout/SymbolsPageTest.kt`

- [ ] **Step 1: Failing test** `every printable ASCII symbol is on the grids`: for both `Symbols1` and `ClassicSymbols1`, the character keys of {page1, `Symbols2`} contain every char in ``!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~``. Also remove `SymbolsExtraRow` from the existing "nothing reachable…" helper.
- [ ] **Step 2: Run → FAIL** (missing `%`).
- [ ] **Step 3:** In `Symbols2` row 1 replace `"¶"` with `"%"`; give `"•"` popup `listOf("¶")`.
- [ ] **Step 4: Run → PASS.** Commit.

### Task 4: Key beside the spacebar

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/layout/SpacebarSideKey.kt`
- Modify: `core/src/main/kotlin/dev/omakey/core/layout/LayoutPreferences.kt` (field + setter + load)
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/KeyboardViewModel.kt` (`display`, after `withPopupSymbols`)
- Modify: `app/src/main/kotlin/dev/omakey/app/settings/SettingsComponents.kt` (new row), `SettingsIndex.kt` (new `SettingKey`)
- Test: `core/src/test/kotlin/dev/omakey/core/layout/SpacebarSideKeyTest.kt`

**Interfaces:**
- Produces: `fun KeyboardLayout.withSpacebarSideKey(character: String): KeyboardLayout`; `LayoutSettings.spacebarSideKey: String = "."`; `LayoutPreferences.setSpacebarSideKey(value: String)`.

- [ ] **Step 1: Failing tests:**
  - `default changes nothing`: `QwertyEnUS.withSpacebarSideKey(".")` is the same instance.
  - `comma replaces the period`: key right of SPACE has label "," and committedText ","; its `code` is unchanged; its popups are "." followed by the old popups minus ",".
  - `nepali danda stays reachable`: on a layout whose key right of space is "।", choosing "," gives popups starting with "।".
  - `no key right of the spacebar`: layout returned unchanged.
  - `emoji accepted`: "😀" (surrogate pair) works; committedText "😀".
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement**: bottom row, the first `KeyType.CHARACTER` key after the SPACE key; if its committedText == character → return `this`; else `copy(label = character, text = character, popupChars = listOf(old) + old popups minus character)`. Pref stored as a string, validated on read: exactly one grapheme (`BreakIterator.getCharacterInstance`), not whitespace, else ".".
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Wire** in `display()` after `withPopupSymbols`. Settings row "Key next to the spacebar" in the Buttons section: chips `. , ? ! ' -` plus "Other…" opening a one-character field. Search keywords: period, comma, full stop, dot.
- [ ] **Step 6: Compile both flavors**; commit. Device check: switch to ",", type in en and ru; long-press shows "."; Nepali shows "।".

### Task 5: Theme tile includes custom themes

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/theme/ThemeCycle.kt`
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/KeyboardViewModel.kt` (`cycleTheme`), plus the constructor/injection of `CustomThemePreferences` (or its `themes` flow) from `OmakeyInputMethodService`
- Test: `core/src/test/kotlin/dev/omakey/core/theme/ThemeCycleTest.kt`

**Interfaces:**
- Produces: `fun nextTheme(currentId: String, presets: List<OmakeyTheme>, customs: List<OmakeyTheme>): OmakeyTheme`

- [ ] **Step 1: Failing tests**: presets then customs in order; last custom wraps to first preset; no customs → presets only; unknown current id (deleted custom) → first preset.
- [ ] **Step 2: Run → FAIL. Step 3: Implement. Step 4: PASS.**
- [ ] **Step 5:** `cycleTheme()` uses `nextTheme(currentTheme.id, Presets.all, customThemes.value)`; remove the "deliberately not in the cycle" KDoc. Commit.

### Task 6: Learned-word edit works, and the keyboard sees it

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/db/LearnedWordEdits.kt` (pure merge/validate)
- Create: `core/src/main/kotlin/dev/omakey/core/predict/LearnedWordsSignal.kt` (cross-process counter on `PreferenceStore`)
- Modify: `core/src/main/kotlin/dev/omakey/core/db/Daos.kt` (`@Transaction` rename-or-merge), `app/.../settings/SettingsOverlays.kt` (edit/remove/delete-all bump the signal; error text on invalid), `app/.../keyboard/LocaleManager.kt` (reload personal model on signal)
- Test: `core/src/test/kotlin/dev/omakey/core/db/LearnedWordEditsTest.kt`

**Interfaces:**
- Produces: `object LearnedWordEdits { fun normalise(input: String, profile: LanguageProfile): String?; fun merge(old: WordEntity, existing: WordEntity?, newWord: String): WordEntity }`; `WordDao.renameOrMerge(locale: String, oldWord: String, newWord: String)` (`@Transaction`, default method using `findExact`/`upsert`/`delete`); `class LearnedWordsSignal(context) { val changes: StateFlow<Int>; fun bump() }`; `LocaleManager.reloadPersonal()`.

- [ ] **Step 1: Failing tests**: `normalise` → lookup form ("Bibek"→"bibek"), null for "", "hello world", "123", a word with non-word chars for the profile; `merge` with existing → frequency summed, explicit = either, lastUsed = max, word = newWord; without existing → old row renamed.
- [ ] **Step 2: Run → FAIL. Step 3: Implement. Step 4: PASS.**
- [ ] **Step 5: Wire**: the dialog shows "Use letters only, one word" on null; Save calls `renameOrMerge` then `signal.bump()`; Remove and Delete all also bump. `LocaleManager` collects `signal.changes` (drop the initial value) and rebuilds the active locale's `PersonalLanguageModel` the way `load` does, then `autocorrectIndex.load(...)` and a new `NgramPredictionEngine` — extract that part of `load` into `reloadPersonal()` so both share it.
- [ ] **Step 6: Compile; commit.** Device check: learn "bibek", edit to "bibekk", type "bibek" + space in a field → no longer protected; rename onto an existing word → no crash.

### Task 7: Language subtypes

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/locale/SubtypeSpec.kt`
- Create: `app/src/main/kotlin/dev/omakey/app/keyboard/SubtypeSync.kt`
- Modify: `app/.../keyboard/OmakeyInputMethodService.kt` (create sync in `onCreate`, override `onCurrentInputMethodSubtypeChanged`)
- Test: `core/src/test/kotlin/dev/omakey/core/locale/SubtypeSpecTest.kt`

**Interfaces:**
- Produces: `data class SubtypeSpec(val localeId: String, val languageTag: String, val subtypeId: Int, val asciiCapable: Boolean, val label: String)`; `fun KeyboardLocale.subtypeSpec(): SubtypeSpec`; `fun localeIdForLanguageTag(tag: String, enabled: List<KeyboardLocale>): String?`.
  - `languageTag` = id with `_`→`-` ("ru-RU"); `subtypeId` = stable FNV-1a 32-bit hash of `"omakey:" + id` (not `String.hashCode`, documented as frozen); `asciiCapable` = `profile.script == Script.LATIN`; `label` = `nativeName`.

- [ ] **Step 1: Failing tests**: ru_RU → "ru-RU", not ASCII-capable, label "Русский"; subtypeId equals a pinned constant for "en_US" and "ru_RU" (frozen forever); `localeIdForLanguageTag("ru-RU")` and `("ru")` → "ru_RU"; unknown → null.
- [ ] **Step 2: FAIL. Step 3: Implement. Step 4: PASS.**
- [ ] **Step 5: `SubtypeSync`** (app): on `localeManager.enabled` change → `InputMethodManager.setAdditionalInputMethodSubtypes(imeId, specs except en_US)` built with `InputMethodSubtype.InputMethodSubtypeBuilder` (`setLanguageTag`, `setSubtypeId`, `setIsAsciiCapable`, `setSubtypeMode("keyboard")`, `setSubtypeNameOverride(label)`, `setOverridesImplicitlyEnabledSubtype(false)`). On `localeManager.active` change → `switchInputMethod(imeId, subtype)` (API 28+; skip below) unless the system's current subtype already matches. `onCurrentInputMethodSubtypeChanged(s)` → `localeIdForLanguageTag` → `localeManager.switchTo(id, remember = true)`; a flag suppresses the echo. en_US maps to the XML subtype (match by language tag).
- [ ] **Step 6: Compile; commit.** Device check: switch to RU in Telegram → red underlines gone for Russian; system language switcher lists languages; if switching doesn't take, set `setOverridesImplicitlyEnabledSubtype(true)` and retest.

### Task 8: Play Console — R8 and edge-to-edge

**Files:**
- Modify: `app/build.gradle.kts` (release: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`)
- Create: `app/proguard-rules.pro`
- Modify: `app/.../settings/SettingsActivity.kt` (`enableEdgeToEdge()`; insets on the root scaffold/list)

- [ ] **Step 1:** `./gradlew :app:assembleSideloadRelease :app:bundlePlayRelease` → note R8 warnings/errors.
- [ ] **Step 2:** Add keep rules only for what R8 reports or what's reached by name: `@Serializable` classes in `dev.omakey.**` (keep `$$serializer`/`Companion` per kotlinx docs if the library's consumer rules don't cover them), extension classes loaded by name (check `extensions-builtin` registration), anything referenced from JSON by class name. Re-run until clean.
- [ ] **Step 3:** Edge-to-edge in `SettingsActivity`; every overlay already uses status/navigation-bar padding — verify the main list does too. Confirm the IME's root pads `WindowInsets.navigationBars` on API 35.
- [ ] **Step 4:** Commit. **Device checklist (release build, upgrade over the installed app, not fresh):** keyboard opens; existing theme and custom themes load; language packs load and suggestions work; learned words list; clipboard history; theme editor save; pack download; Settings top/bottom not under system bars on Android 15.

### Task 9: HTML mock — Settings rows and grid colours (approval gate)

**Files:**
- Create: scratchpad `settings-mock.html` (throwaway, not committed), published as an Artifact.

- [ ] **Step 1:** Mock the Suggestion bar + Buttons section and the Gestures screen, current vs proposed, in omakey's dark settings palette, at phone width; proposed rows: card per group, 16 dp padding, 16 dp gap text↔switch, 14 dp row padding, dividers, muted descriptions, rewritten copy.
- [ ] **Step 2:** Same page: grid-keyboard swatches for 6 backgrounds (dark grey, near-black, navy, teal, beige, white) with border contrast bands 1.3, 1.6, 2.0, 3.0 (current).
- [ ] **Step 3:** Developer picks; record the chosen band and any copy edits in the spec. **Tasks 10–11 wait for this.**

### Task 10: Calmer derived grid colours

**Files:**
- Modify: `core/src/main/kotlin/dev/omakey/core/theme/ThemeDerivation.kt` (`fromBackground` gridBorder; key separation)
- Test: `core/src/test/kotlin/dev/omakey/core/theme/ThemeDerivationTest.kt`

**Interfaces:**
- Produces: `ThemeDerivation.GRID_BORDER_CONTRAST: ClosedFloatingPointRange<Double>` (from Task 9's pick).

- [ ] **Step 1: Failing test** `grid border is soft but visible`: for the 6 mock backgrounds plus 20 random seeded ones, `contrast(gridBorder, background)` ∈ `GRID_BORDER_CONTRAST`.
- [ ] **Step 2: FAIL (current ≥3:1). Step 3:** border = background mixed toward `textOn(background)` in small steps until the band's lower bound; key colour nudged off the background if contrast < 1.08. **Step 4: PASS**, existing contrast tests still pass. Commit.

### Task 11: Settings redesign

**Files:**
- Modify: `app/.../settings/SettingsComponents.kt` (`SettingsSection`, `UnindexedSettingToggle`, `UnindexedClickableSettingRow`, the slider rows in `GestureSettingsSection`), every description string across `SettingsActivity.kt`, `SettingsComponents.kt`, `LanguageSettings.kt`, `ThemeSettings.kt`
- Modify: `app/.../settings/SettingsIndex.kt` only if a title changes (search keeps old words as keywords)
- Test: existing `app/src/test/.../settings/*` must pass

- [ ] **Step 1:** Shared components per Task 9's approved mock (spacing, dividers, muted descriptions via `onSurfaceVariant`).
- [ ] **Step 2:** Rewrite every description per the approved copy. Keep each to one sentence where possible.
- [ ] **Step 3:** `:app:testDebugUnitTest` (settings search index tests) PASS; compile both flavors. Commit. Device check: scroll every screen at default and largest font size.

### Task 12: Release 5.7.0

**Files:** `app/build.gradle.kts` (versionCode next, versionName 5.7.0), `CHANGELOG.md`, `README.md`, `docs/play-closed-testing.md` (new)

- [ ] **Step 1:** Full check: compile all modules, `:core:testDebugUnitTest :app:testDebugUnitTest`, `node --test .claude/hooks/test/*.test.mjs`.
- [ ] **Step 2:** Version bump; CHANGELOG (user words); README for user-visible changes; Play guide (create release on closed track, upload `.aab`, release notes, testers list/link, review wait, Play App Signing note, mapping file is inside the bundle).
- [ ] **Step 3:** Build `:app:assembleSideloadRelease` and `:app:bundlePlayRelease`; report paths and SHA-256.
- [ ] **Step 4:** Commit. **Stop:** ask the developer before installing, tagging, pushing or creating the GitHub release.
