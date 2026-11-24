# Compact mode — design (short)

Date: 2026-11-23 · Status: awaiting approval

## Intent
A user coming from Fleksy finds the spacebar row too big. Fleksy had a mode that hides the bottom
row and leaves only the letter rows, with gestures standing in for space. omakey already has
Fleksy-style surface gestures, so compact mode mostly means not drawing that row.

## Goals
- A **Compact** tile in the quick access panel (the leftmost suggestion-bar button). Tapping it
  turns compact mode on; it's highlighted while compact is on, and tapping again turns it off,
  the same way the Grid layout tile works.
- While compact is on, the letter layout's **bottom row is not drawn at all**: spacebar, enter,
  symbols, emoji/language slot and the key next to the spacebar are gone. The keyboard gets
  **one row shorter** and the remaining keys stay the same size.
- **Swipe right always types a space while compact is on**, whatever "Swipe right for space" is set
  to. The saved setting doesn't change.
- The setting survives restarts.

## Non-goals
- No replacement for the hidden keys (enter, symbols, emoji). Hidden means hidden: the user chose
  that.
- No two-finger swipe to toggle.
- No Fleksy-style gestures for enter or symbols.

## Constraints and scope
- Compact applies only to **letter layouts**. Number pads that a field opens (phone, PIN, number)
  keep their bottom row, or a number field would have no enter or delete key. The symbols page
  stays as it is; it just can't be reached while compact is on.
- Row height stays `keyboardHeightDp / baseRowCount`. Compact only reduces the number of rows drawn
  (`KeyboardRoot.effectiveRows`), so the keyboard shrinks with no extra height logic. Docked,
  one-handed and floating all work the same way.
- Not a Room change: it's a preference, stored next to the other layout settings.

## Approaches
- **A. Drop the bottom row at draw time (chosen).** One rule in one place, so every language and
  language pack works without changes, and the keyboard shrinks the way the user asked.
- B. Keep the height and spread it over three rows: bigger keys but no screen saved, which misses
  the point. Rejected.
- C. Separate compact layout files per language: duplicates every layout and pack. Rejected.

## Calls I made (correct any)
- The setting lives in `LayoutPreferences`, because it's about the keyboard's shape, like height
  and placement.
- Quick access only, with no Settings switch, unless you want one there as well (it could go
  under Layout, and settings search would find it as "compact").
- The tile is placed after "Grid layout".

## Left to the builder
- The tile icon (a Phosphor icon in the existing style).
- Whether the settings-screen resize preview should show the compact height while compact is on.

## Proving it
Unit-test the row-dropping rule (letters dropped; number pads and symbols kept) and the
swipe-right-space override. The tile, the shorter keyboard and the gesture are not proven by
tests: they need trying on the phone.
