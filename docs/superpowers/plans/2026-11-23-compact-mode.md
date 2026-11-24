# Compact Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "Compact" tile in quick access that hides the letter layout's bottom row (spacebar row), so the keyboard is one row shorter, and makes swipe right always type a space while compact is on.

**Architecture:** One pure rule in `core` (`CompactMode`) decides which rows are drawn and whether swipe right types a space. The preference lives in `LayoutSettings`. `KeyboardRoot` draws the rows that rule returns. Row height still divides by the letter layout's full row count, so the keyboard shrinks by exactly one row. Quick access gets a toggle tile.

**Tech Stack:** Kotlin, Jetpack Compose, SharedPreferences via `PreferenceStore`, JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-23-compact-mode-design.md`

## Global Constraints
- JDK 17: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` before every Gradle command.
- Compact applies to letter layouts only. Number pads (`NumberPads.IDS`) and symbols pages (`Layouts.Symbols1.id`, `Layouts.Symbols2.id`) keep every row.
- Hidden means hidden: no replacement for enter, symbols, emoji or language.
- No two-finger gesture. The quick access tile is the only toggle; there's no Settings switch.
- Swipe right types space while compact applies, without changing `GestureSettings.swipeRightForSpace`.
- Row height stays `keyboardHeightDp / baseRowCount`. Don't touch height logic.
- No Room change. Preference key `compact_mode`, default `false`.
- Work on branch `feature/compact-mode`, not `main`. Commit only with the developer's go-ahead. Never push.
- Lint must pass with zero errors.

## Review Focus
1. **Compact turned on while the symbols page is showing.** The symbols page keeps its bottom row, so "ABC" still returns to letters, and the letters then show compact. Covered by `symbols pages keep every row`.
2. **Compact on while a number field is focused.** The number pad keeps enter and delete. Covered by `number pads keep every row`.
3. **A one-row layout (malformed pack).** Compact must not produce zero rows: return the rows unchanged. Covered by `a single-row layout is left alone`.
4. **Floating and one-handed placement.** The keyboard shrinks by one row in every placement, and the floating window's frame follows the shorter grid. Device check only, in Task 2 Step 6.
5. **Swipe right with "Swipe right for space" off.** It types a space while compact is on, and goes back to doing nothing when compact is off. The pointer handler must restart when compact flips, or it keeps the old value. Covered by the `swipe right…` tests plus the device check.

---

### Task 1: Compact rule and preference (core)

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/layout/CompactMode.kt`
- Modify: `core/src/main/kotlin/dev/omakey/core/layout/LayoutPreferences.kt` (field in `LayoutSettings`, setter, `update` write, `load` read, `KEY_COMPACT_MODE`)
- Test: `core/src/test/kotlin/dev/omakey/core/layout/CompactModeTest.kt`

**Interfaces:**
- Produces:
  - `LayoutSettings.compactMode: Boolean = false` (KDoc: what it hides, and that swipe right becomes space)
  - `LayoutPreferences.setCompactMode(enabled: Boolean)`
  - `object CompactMode`:
    - `fun applies(compact: Boolean, layoutId: String): Boolean`: true iff `compact` and `layoutId` is in neither `NumberPads.IDS` nor `{Layouts.Symbols1.id, Layouts.Symbols2.id}`
    - `fun rows(layout: KeyboardLayout, compact: Boolean): List<KeyRow>`: `layout.rows.dropLast(1)` when `applies(compact, layout.id)` and `layout.rows.size > 1`, otherwise `layout.rows` (the same instance, so Compose memoisation holds)
    - `fun swipeRightTypesSpace(setting: Boolean, compactApplies: Boolean): Boolean` = `setting || compactApplies`

- [ ] **Step 1: Create the branch.** `git switch -c feature/compact-mode`

- [ ] **Step 2: Write the failing tests** in `CompactModeTest`:

```kotlin
@Test fun `off draws every row`() =
    assertSame(Layouts.QwertyEnUS.rows, CompactMode.rows(Layouts.QwertyEnUS, compact = false))
@Test fun `on drops the letter layout's bottom row`() =
    assertEquals(Layouts.QwertyEnUS.rows.dropLast(1), CompactMode.rows(Layouts.QwertyEnUS, compact = true))
@Test fun `symbols pages keep every row`() {
    assertSame(Layouts.Symbols1.rows, CompactMode.rows(Layouts.Symbols1, compact = true))
    assertSame(Layouts.Symbols2.rows, CompactMode.rows(Layouts.Symbols2, compact = true))
}
@Test fun `number pads keep every row`() = NumberPads.IDS.forEach {
    assertFalse(CompactMode.applies(compact = true, layoutId = it))
}
@Test fun `a single-row layout is left alone`() {
    val one = Layouts.QwertyEnUS.copy(rows = Layouts.QwertyEnUS.rows.take(1))
    assertSame(one.rows, CompactMode.rows(one, compact = true))
}
@Test fun `swipe right types space while compact applies even with the setting off`() =
    assertTrue(CompactMode.swipeRightTypesSpace(setting = false, compactApplies = true))
@Test fun `swipe right follows the setting when compact is off`() {
    assertFalse(CompactMode.swipeRightTypesSpace(setting = false, compactApplies = false))
    assertTrue(CompactMode.swipeRightTypesSpace(setting = true, compactApplies = false))
}
@Test fun `compact is off by default`() = assertFalse(LayoutSettings().compactMode)
```

- [ ] **Step 3: Run them and check they fail.**
Run: `./gradlew :core:testDebugUnitTest --tests 'dev.omakey.core.layout.CompactModeTest'`
Expected: compile failure on `CompactMode` / `compactMode`.

- [ ] **Step 4: Implement** `CompactMode` and the `LayoutSettings`/`LayoutPreferences` changes, following the existing pattern for `edgePadding` (field, `setX = update { it.copy(...) }`, `putBoolean` in `update`, `getBoolean(KEY_COMPACT_MODE, false)` in `load`, constant `KEY_COMPACT_MODE = "compact_mode"`).

- [ ] **Step 5: Run the tests and check they pass.** Same command. Expected: 8 tests, PASS.

- [ ] **Step 6: Commit** (with the developer's go-ahead):
`git add core/src/main/kotlin/dev/omakey/core/layout/CompactMode.kt core/src/main/kotlin/dev/omakey/core/layout/LayoutPreferences.kt core/src/test/kotlin/dev/omakey/core/layout/CompactModeTest.kt && git commit -m "Add compact mode rule and preference"`

### Task 2: Draw compact, override swipe right, quick access tile (app)

**Files:**
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/ui/KeyboardRoot.kt:189` (`effectiveRows`)
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/ui/KeyGrid.kt` (the four `gestureSettings.swipeRightForSpace` call sites near lines 311/360/399/467, and the `pointerInput` keys at line 203)
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/KeyboardViewModel.kt` (add `toggleCompactMode()` next to `toggleLayoutMode()`, ~line 1940)
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/ui/KeyboardPlacementUi.kt:295` (new tile after "Grid layout")
- Modify: `core/src/main/kotlin/dev/omakey/core/icons/PhosphorIcons.kt` (new `PhosphorRows`)
- Modify: `CHANGELOG.md` (Unreleased → Added), `README.md` (one plain sentence in the features list, no class names), `docs/internal/decisions.md` (short entry: compact = drop bottom row at draw time; swipe right forced to space; quick access only; letter layouts only)

**Interfaces:**
- Consumes: `CompactMode.rows`, `CompactMode.applies`, `CompactMode.swipeRightTypesSpace`, `LayoutSettings.compactMode`, `LayoutPreferences.setCompactMode` (Task 1)
- Produces: `KeyboardViewModel.toggleCompactMode()`, `PhosphorRows: ImageVector`

- [ ] **Step 1: Draw compact.** In `KeyboardRoot`, `val effectiveRows = CompactMode.rows(uiState.layout, layoutSettings.compactMode)`. Leave `rowHeightDp` (divides by `baseRowCount`) as it is, so `gridHeightDp` comes out one row shorter.

- [ ] **Step 2: Swipe right.** In `KeyGrid`, compute once:
`val swipeRightSpace = CompactMode.swipeRightTypesSpace(gestureSettings.swipeRightForSpace, CompactMode.applies(layoutSettings.compactMode, uiState.layout.id))`.
Pass it at the four call sites in place of `gestureSettings.swipeRightForSpace`, and add `swipeRightSpace` to the `pointerInput(...)` keys at line 203 so the handler restarts when compact flips.

- [ ] **Step 3: View model.** `fun toggleCompactMode() = layoutPreferences.setCompactMode(!layoutPreferences.settings.value.compactMode)`

- [ ] **Step 4: Tile and icon.** Add `PhosphorRows` (Phosphor "Rows" glyph, the same stroke style and builder as `PhosphorGrid`). Add `QuickTile("Compact", PhosphorRows, layoutSettings.compactMode) { feedback.onKeyPress(); viewModel.toggleCompactMode() }` after "Grid layout". Give `QuickAccessPanel` a `compact: Boolean` parameter, passed from `KeyboardRoot:469` as `layoutSettings.compactMode` (the panel's other state comes in the same way, e.g. `incognito`).

- [ ] **Step 5: Docs.** CHANGELOG, README and the decisions.md entry, as listed under Files.

- [ ] **Step 6: Run the full gate.**
```
./gradlew :core:compileDebugKotlin :app:compileDebugKotlin :extensions-builtin:compileDebugKotlin
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug
node --test .claude/hooks/test/*.test.mjs
./gradlew :app:lintSideloadRelease :app:lintPlayRelease :core:lintDebug :extensions-builtin:lintDebug
```
Expected: everything passes; lint reports 0 errors.
Not device-tested. Tell the developer to try on the phone:
- Toggle the tile: the keyboard loses its bottom row and shrinks by one row, docked, one-handed and floating.
- With "Swipe right for space" off: swipe right types a space in compact, and does nothing after toggling compact off.
- A number field still shows its pad with enter and delete.
- Compact still applies after restarting the keyboard.

- [ ] **Step 7: Commit** (with the developer's go-ahead):
`git add -A app core/src/main/kotlin/dev/omakey/core/icons CHANGELOG.md README.md && git commit -m "Compact mode: quick access tile hides the spacebar row"` (`decisions.md` is gitignored and stays local).
