# Spacebar hold: trackpad with Select All

## Intent
A Fleksy user asked for Fleksy's spacebar hold: hold the spacebar, the keys disappear, a
"SELECT ALL" pill appears, and swiping up onto it selects all the text. The developer wants the
same look (the keys hide, as in the Fleksy screenshot) on top of omakey's existing hold-to-move-cursor.

## Today
Hold the spacebar for 400 ms and the cursor mode starts (`KeyGrid.kt`, the `SpecialKeyCode.SPACE`
branch of `longPressJob`). Sliding sideways moves the cursor one step per 10 dp. Lifting without
moving types a space. Vertical movement is ignored. Select All already exists (`viewModel.onSelectAll()`,
the same action as the Tools tab).

## Design
- When the hold engages (same moment, same buzz as today), the key grid draws **no keys**: only
  the keyboard background, a **"SELECT ALL" pill** centred near the top of the key area, and a
  **circle under the finger** that follows it. The suggestion bar is untouched, and the keyboard
  keeps its height.
- Sideways sliding moves the cursor, unchanged.
- Sliding **up onto the pill** highlights it (small buzz). While highlighted, sideways movement
  does not move the cursor. Sliding off un-highlights it.
- **Lift on the pill**: select all. **Lift elsewhere**: keys come back; if the cursor never moved,
  a space is typed (unchanged).
- Pill and circle use the theme's key colours so every theme works.
- Compact mode has no spacebar, and accessible mode (TalkBack) has no gestures; both keep Select
  All in the Tools tab.

## Goals / non-goals
- Goal: Fleksy's gesture, with the hidden keys, without losing cursor drag or hold-release space.
- Non-goal: vertical cursor movement (up is reserved for the pill), a two-finger trackpad, or
  extending a selection by dragging.
- Anti-goal: selecting all by accident. Only a lift that is on the pill selects.

## Approaches
1. **Hold + slide up onto a pill over a blank pad (picked).** Up is the only direction the hold
   doesn't use, and it matches Fleksy and the email.
2. Hold alone selects all on release: rejected; releasing without moving already types a space.
3. Tap the pill with a second finger: rejected; awkward one-handed and not what Fleksy does.

## Left to the builder
- "On the pill" is a hit test against the pill's drawn bounds, slightly enlarged, decided in a
  `core` function with JVM tests. The keys-hiding and drawing live in `KeyGrid`.
- Exact pill size, circle size and fade timing.

Not device-tested until the developer tries it on the phone.
