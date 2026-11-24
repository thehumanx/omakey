# Split Keyboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Gboard-style split keyboard that turns on automatically on wide screens (≥ 600 dp) when docked, with a Quick access "Split" tile that overrides it per screen class.

**Architecture:** All rules live in `core` with JVM tests:
- `SplitLayout` cuts rows and inserts an invisible `SPACER` gap key.
- `LayoutSettings` holds three new prefs and a pure toggle rule.
- `KeyBoundsCache` replaces a row's cached key bounds wholesale, so no ghost keys survive a change in the rows.

The app passes the split rows (memoised) to the existing key grid, draws nothing for a spacer, and adds the tile and one Settings switch.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-23-split-keyboard-design.md`

## Global Constraints
- JDK 17: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk`.
- Wide screen: `screenWidthDp >= 600`. Gap: **25%** of each split row's width.
- Defaults:
  - `splitOnWide = true`
  - `splitOnNarrow = false`
  - `splitDuplicateKeys = true`
- Pref keys:
  - `split_on_wide`
  - `split_on_narrow`
  - `split_duplicate_keys`
- Only `DOCKED` splits. Number pads (`NumberPads.IDS`) never split; letter layouts and symbol pages do.
- Autocorrect geometry, the eval floors and `golden/en_us.txt` stay untouched.
- Every `Json` keeps `ignoreUnknownKeys = true`. No Room changes.
- Lint must pass with zero errors. Touch and layout behaviour is not device-tested; say so.

## Review Focus
1. Split turned off (tile, rotation, or compact toggled) must leave no ghost key bounds where the gap or the duplicates were. → Task 3 tests.
2. A pack layout whose row already contains `SPACER` indents: spacers stay on their own side and aren't counted as letters. → Task 1 test.
3. A row with no CHARACTER keys is left exactly as it is. → Task 1 test.
4. Recomposition: the split rows must keep the same identity across recompositions, or every keystroke redraws the grid. → `remember` in Task 4.
5. Tapping the tile while floating or one-handed docks the keyboard and splits it, never a silent no-op. → Task 2 test.

---

### Task 1: `SplitLayout` row cutting

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/layout/SplitLayout.kt`
- Test: `core/src/test/kotlin/dev/omakey/core/layout/SplitLayoutTest.kt`

**Interfaces (Produces):**
- `object SplitLayout`, with constants `WIDE_SCREEN_DP = 600` and `GAP_FRACTION = 0.25f`.
- `fun isWide(screenWidthDp: Int): Boolean`
- `fun active(placement: KeyboardPlacement, screenWidthDp: Int, splitOnWide: Boolean, splitOnNarrow: Boolean): Boolean`: DOCKED only; uses `splitOnWide` when wide, `splitOnNarrow` otherwise.
- `fun applies(layoutId: String): Boolean`: `layoutId !in NumberPads.IDS`.
- `fun splitRow(row: KeyRow, duplicateMiddle: Boolean): KeyRow`
- `fun rows(layoutId: String, rows: List<KeyRow>, split: Boolean, duplicateMiddle: Boolean): List<KeyRow>`: returns the **same list instance** when `!split || !applies(layoutId)`.
- `val GAP_KEY_CODE = 0`. The gap is `KeyDefinition("", GAP_KEY_CODE, widthWeight = g, keyType = KeyType.SPACER)`.

**`splitRow` algorithm:**
- **A row containing the SPACE key:** not cut. Raise the space key's weight by `W * f / (1 - f)`, where W is the row's total weight and f is `GAP_FRACTION`.
- **A row with no CHARACTER keys:** returned unchanged (same instance).
- **Otherwise,** let `c` be the indices of the CHARACTER keys and `n = c.size`.
  - Even n: left = `keys[0..c[n/2-1]]`, right = the rest.
  - Odd n, duplicating: left = `keys[0..c[n/2]]`, right = `keys[c[n/2]..]` (the middle key appears in both).
  - Odd n, not duplicating: left = `keys[0..c[n/2]]`, right = the rest.
  - Scale every key of the lighter half by `heavier / lighter`, so the halves weigh the same (H each).
  - Result: `left + gap(2H * f / (1 - f)) + right`.

- [ ] **Step 1: Write the failing tests.** Use a helper `labels(row)` that drops spacers and maps to labels, and `gapFraction(row)` = spacer weight / total weight.
  - `QWERTY top row splits five and five`: `[q,w,e,r,t]` / `[y,u,i,o,p]` around one spacer.
  - `odd row repeats its middle letter like Gboard`: row 1 gives `a s d f g | g h j k l`; row 2 gives `⇧ z x c v | v b n m ⌫`.
  - `without duplicates the middle letter goes left`: row 1 gives `a s d f g | h j k l`, and both halves have equal weight.
  - `every split row's gap is a quarter of its width`: for every non-space row of every layout in `Layouts.all`, `gapFraction == 0.25f ± 1e-4`.
  - `halves weigh the same`: the left and right weight sums are equal for every split row of `Layouts.all`.
  - `space row keeps one spacebar that spans the gap`: QWERTY's bottom row has the same key count and no spacer. Its space weight is `4 + W/3` (`f/(1-f) = 1/3`).
  - `eleven-letter row repeats the sixth`: a synthetic 11-key row duplicates key index 5.
  - `existing spacer indents stay on their side and are not letters`: the row `[SPACER 0.5, a..i (9), SPACER 0.5]` splits at `e`, the leading spacer stays first, and the trailing one stays last.
  - `a row with no letters is untouched`: `assertSame`.
  - `number pads never split` and `symbol pages split`: `applies`.
  - `rows returns the same list when not split`: `assertSame`.
  - `active` cases: docked+wide+onWide → true; docked+599dp → uses onNarrow; 600 → wide; FLOATING / ONE_HANDED_LEFT / ONE_HANDED_RIGHT → false.
- [ ] **Step 2: Run, expect FAIL (unresolved `SplitLayout`).** `./gradlew :core:testDebugUnitTest --tests '*SplitLayoutTest*'`
- [ ] **Step 3: Implement**, with a KDoc that states the Gboard rule and its source.
- [ ] **Step 4: Run, expect PASS.**

### Task 2: Prefs and the tile's toggle rule

**Files:**
- Modify: `core/src/main/kotlin/dev/omakey/core/layout/LayoutPreferences.kt`
  - `LayoutSettings` gets three fields.
  - Read and write them with the pref keys above.
  - Add setters, plus `fun toggleSplit(screenWidthDp: Int) = update { SplitLayout.toggled(it, screenWidthDp) }`.
- Modify: `SplitLayout.kt`. Add `fun toggled(settings: LayoutSettings, screenWidthDp: Int): LayoutSettings`:
  - Not DOCKED: placement becomes DOCKED and the current screen class's pref becomes true.
  - DOCKED: flip the current class's pref.
- Test: `SplitLayoutTest.kt`

- [ ] **Step 1: Failing tests:**
  - `defaults split wide screens only, with duplicates`.
  - `tile flips only the current screen class`: wide → `splitOnWide` false, `splitOnNarrow` untouched; and the narrow case.
  - `tile while floating docks and splits`.
  - `tile while one-handed docks and splits`.
- [ ] **Step 2: Run, expect FAIL.** **Step 3: Implement.** **Step 4: Run, expect PASS.**

### Task 3: `KeyBoundsCache`, so no ghost keys

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/layout/KeyBoundsCache.kt`
- Test: `core/src/test/kotlin/dev/omakey/core/layout/KeyBoundsCacheTest.kt`

**Interfaces (Produces):** `object KeyBoundsCache`, with `fun <V> merge(current: Map<Int, V>, rowIndex: Int, measured: Map<Int, V>, rowCount: Int): Map<Int, V>?`.
- Keys are `row * 1000 + keyIndex`, as today.
- The result:
  - drops every entry of `rowIndex`, and every entry whose row is ≥ `rowCount`;
  - adds `measured`, which maps keyIndex to a value;
  - returns `null` when that equals `current`, so the caller skips the state write and keeps today's no-op-per-frame fix.

- [ ] **Step 1: Failing tests:**
  - `a row that lost keys drops the extra entries`.
  - `rows beyond the drawn count are dropped`.
  - `an unchanged row returns null`.
  - `other rows are kept`.
- [ ] **Step 2: Run, expect FAIL.** **Step 3: Implement.** **Step 4: Run, expect PASS.**

### Task 4: App wiring, docs and the gate

**Files:**
- `app/.../keyboard/ui/KeyboardRoot.kt` (~line 194):
  - `val splitActive = SplitLayout.active(layoutSettings.placement, screenWidthDp, layoutSettings.splitOnWide, layoutSettings.splitOnNarrow)`. Move it below `screenWidthDp`.
  - `effectiveRows = remember(compactRows, splitActive, layoutSettings.splitDuplicateKeys, uiState.layout.id) { SplitLayout.rows(...) }`.
  - Pass `split = splitActive` to `QuickAccessPanel`.
- `app/.../keyboard/ui/KeyGrid.kt`:
  - The bounds callback (~line 595) uses `KeyBoundsCache.merge` with the current row count (via `rememberUpdatedState(effectiveRows.size)`), and writes only when the result is non-null.
  - `KeyRowView` draws `Spacer(Modifier.weight(key.widthWeight).fillMaxHeight())` for a `KeyType.SPACER` key instead of `KeyCell`, and leaves spacers out of `measured` (a gap is no key).
- `app/.../keyboard/KeyboardViewModel.kt`: `fun toggleSplit(screenWidthDp: Int)` calls `layoutPreferences.toggleSplit(...)` and closes quick access, like `setPlacement`.
- `app/.../keyboard/ui/KeyboardPlacementUi.kt`:
  - `QuickAccessPanel(split: Boolean, screenWidthDp: Int, …)`.
  - The tile `QuickTile("Split", PhosphorSplit, split) { feedback.onKeyPress(); viewModel.toggleSplit(screenWidthDp) }` goes next to the Compact tile.
- `core/.../icons/PhosphorIcons.kt`: `val PhosphorSplit = pathIcon("M24,64h88v128h-88z M144,64h88v128h-88z")`.
- Settings:
  - `SettingsIndex.kt`: `SPLIT_DUPLICATE_KEYS("Split keyboard middle keys", SettingsSectionKey.KEYS, listOf("split", "foldable", "tablet", "landscape", "duplicate"))`.
  - `SettingsComponents.kt`: a `SettingToggle` after `HOME_ROW_HIGHLIGHT` with the description "On a split keyboard, repeat the middle keys (G, V) on both sides, like Gboard."
- Docs:
  - CHANGELOG Added: **Split keyboard**. On wide screens (unfolded foldables, tablets, phones in landscape) the keys split in two, like Gboard. A Split tile in Quick access turns it off or on, and omakey remembers that separately for wide and narrow screens.
  - README: a Quick access bullet, plus a note that landscape now splits by default.
  - `docs/internal/decisions.md`: §89.

- [ ] **Steps:** implement the above, then run the full gate:
  ```
  ./gradlew :core:compileDebugKotlin :app:compileDebugKotlin :extensions-builtin:compileDebugKotlin
  ./gradlew :core:testDebugUnitTest :app:testDebugUnitTest
  ./gradlew :app:assembleDebug
  node --test .claude/hooks/test/*.test.mjs
  ./gradlew :app:lintSideloadRelease :app:lintPlayRelease :core:lintDebug :extensions-builtin:lintDebug
  ```
  Expected: every task succeeds, with zero lint errors.
