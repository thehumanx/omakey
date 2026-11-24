# Spacebar Trackpad + Select All Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Holding the spacebar hides every key, shows a "SELECT ALL" pill and a circle under the finger; sliding up onto the pill and lifting selects all text, while sideways sliding still moves the cursor.

**Architecture:** The pill's geometry and the "is the finger on the pill" rule live in a new `core` object `SpacebarTrackpad` with JVM tests. `KeyGrid` reuses the symbol-mode pattern already there: an `Animatable` fades the key `Column` out and a new `TrackpadOverlay` composable in, driven by state the existing `cursorDragActive` branch of the pointer loop writes.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-23-spacebar-select-all-design.md`

## Global Constraints
- JDK 17: `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk`.
- Only `TextEditor` touches `InputConnection`: Select All goes through the existing `viewModel.onSelectAll()`.
- Hold delay stays 400 ms; cursor step stays 10 dp; lift without moving still types a space.
- Pill label text: `SELECT ALL`.
- Colours come from the theme only (no hard-coded colours): pill `theme.keyBackground` / label `theme.labelOn(theme.keyBackground)`; highlighted pill `theme.accent` / `theme.onAccent`; circle `theme.accent` at 35% alpha.
- No new setting. Compact mode and accessible mode need no code (no spacebar / no gestures).
- Lint must pass with zero errors. Touch behaviour is not device-tested; say so.

## Review Focus
1. Thumb overshoots upward past the pill (even above the key grid, negative y) and lifts: should still select all. → `onPill` has no top bound (Task 1 test).
2. Finger leaves the pill sideways/down and keeps sliding: the cursor must not jump by the distance travelled while on the pill. → anchor reset on leaving (Task 2).
3. A second finger lands during the hold: must not type (existing `!cursorDragActive` guard already covers it; don't break it).
4. Cursor moved first, then slid onto the pill and lifted: selects all, no space typed.
5. Floating / one-handed narrow keyboards: pill centred on the grid's own width, never wider than the grid. → Task 1 test.

---

### Task 1: `SpacebarTrackpad` geometry in core

**Files:**
- Create: `core/src/main/kotlin/dev/omakey/core/gesture/SpacebarTrackpad.kt`
- Test: `core/src/test/kotlin/dev/omakey/core/gesture/SpacebarTrackpadTest.kt`

**Interfaces:**
- Produces:
  - `object SpacebarTrackpad`
  - `data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)` (nested)
  - `fun pill(gridWidth: Float, pillWidth: Float, pillHeight: Float, topMargin: Float): Bounds` — centred horizontally; width clamped to `gridWidth`.
  - `fun onPill(x: Float, y: Float, pill: Bounds, slop: Float): Boolean` — true when `x in [left-slop, right+slop]` and `y <= bottom + slop`. No top bound (upward overshoot counts).

- [ ] **Step 1: Write the failing tests**
  - `pill is centred on the grid`: `pill(400f, 200f, 50f, 10f) == Bounds(100f, 10f, 300f, 60f)`.
  - `pill never exceeds a narrow grid`: `pill(150f, 200f, 50f, 10f)` has `left == 0f`, `right == 150f`.
  - `a finger inside the pill is on it`: `onPill(200f, 30f, Bounds(100f,10f,300f,60f), slop = 8f)` true.
  - `slop just below the pill still counts`: `onPill(200f, 67f, …, 8f)` true; `onPill(200f, 69f, …, 8f)` false.
  - `overshooting above the pill, even above the grid, still counts`: `onPill(200f, -40f, …, 8f)` true.
  - `beside the pill does not count`: `onPill(90f, 30f, …, 8f)` false; `onPill(309f, 30f, …, 8f)` false.
  - `the resting spacebar position is not on the pill`: `onPill(200f, 380f, …, 8f)` false.

- [ ] **Step 2: Run, expect FAIL (unresolved `SpacebarTrackpad`)**
  Run: `./gradlew :core:testDebugUnitTest --tests '*SpacebarTrackpadTest*'`

- [ ] **Step 3: Implement** with a KDoc explaining the gesture and why there is no top bound.

- [ ] **Step 4: Run, expect PASS** (same command).

- [ ] **Step 5: No commit** — the developer commits this branch themselves.

### Task 2: Trackpad mode in `KeyGrid`

**Files:**
- Modify: `app/src/main/kotlin/dev/omakey/app/keyboard/ui/KeyGrid.kt` (state near line 172; the SPACE branch at ~293; the UP branch at ~387; the `cursorDragActive` MOVE branch at ~413; the key `Column` alpha at ~520; overlays after ~583)
- Modify: `CHANGELOG.md` (`[Unreleased]` → Added), `README.md` (gestures section, non-technical).

**Interfaces:**
- Consumes: `SpacebarTrackpad.pill`, `SpacebarTrackpad.onPill`, `SpacebarTrackpad.Bounds`.

- [ ] **Step 1: State.** Beside `accentDragState`: `var trackpadFinger by remember { mutableStateOf<Offset?>(null) }` (non-null = trackpad shown) and `var trackpadOnPill by remember { mutableStateOf(false) }`. A `trackpadAlpha` `Animatable` driven by `trackpadFinger != null` with `tween(160)`, like `symbolModeAlpha`.
- [ ] **Step 2: Engage.** In the SPACE branch, after setting `cursorDragActive`: `onPressedKeyChange(null)`, `tapPreview.hide()`, `trackpadFinger = down.position`, `trackpadOnPill = false`.
- [ ] **Step 3: Move.** In the `cursorDragActive` MOVE branch: update `trackpadFinger`; compute the pill with `pill(size.width, 160.dp, 44.dp, 16.dp)` (px) and `onPill(..., slop = 12.dp)`. Entering the pill: `feedback.onKeyPress()`, `trackpadOnPill = true`. Leaving: `trackpadOnPill = false`, `cursorDragAnchorX = change.position.x` (no jump). While on the pill, skip the cursor-step code.
- [ ] **Step 4: Lift.** In the UP `cursorDragActive` branch: if `trackpadOnPill` → `feedback.onKeyPress()`, `viewModel.onSelectAll()`; else the existing space-if-not-moved. Then `trackpadFinger = null`, `trackpadOnPill = false`. Also clear both wherever the touch is cancelled/settled otherwise (the `awaitEachGesture` end), so the keys can never stay hidden.
- [ ] **Step 5: Hide keys.** Column alpha becomes `1f - maxOf(symbolModeAlpha.value, trackpadAlpha.value)`; hide the tap-preview bubble while `trackpadAlpha.value > 0f`.
- [ ] **Step 6: Overlay.** `private fun TrackpadOverlay(finger: Offset?, onPill: Boolean, alpha: Float, theme: OmakeyTheme, fontFamily, gridHeightDp)`: full-grid `Box` on `theme.keyboardBackground`; pill at the same bounds Step 3 computes (rounded, centred, 16 dp from top) with `SELECT ALL`; a 48 dp circle centred on `finger` (keep the last position through the fade-out, as `lastAccentDragState` does). Colours per Global Constraints. Drawn only while `trackpadAlpha.value > 0f`.
- [ ] **Step 7: Docs.** CHANGELOG Added: "**Hold the spacebar for Select All.** Holding the spacebar now clears the keys into a trackpad: slide sideways to move the cursor, or slide up onto Select all and lift to select everything." README: one matching bullet.
- [ ] **Step 8: Verify** — the full gate from AGENTS.md:
  ```
  ./gradlew :core:compileDebugKotlin :app:compileDebugKotlin :extensions-builtin:compileDebugKotlin
  ./gradlew :core:testDebugUnitTest :app:testDebugUnitTest
  ./gradlew :app:assembleDebug
  node --test .claude/hooks/test/*.test.mjs
  ./gradlew :app:lintSideloadRelease :app:lintPlayRelease :core:lintDebug :extensions-builtin:lintDebug
  ```
  Expected: all BUILD SUCCESSFUL, lint zero errors.
- [ ] **Step 9:** Record §88 in `docs/internal/decisions.md` (trackpad on hold; no top bound on the pill; anchor reset).
