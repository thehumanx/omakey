# Split keyboard (Gboard-style)

## Intent
A Fleksy user on a foldable asked for a split keyboard: when the phone is unfolded, a
full-width keyboard is too wide to thumb-type. The developer's direction is "do as Gboard does".
It is useful on any wide screen: an unfolded foldable, a tablet, or a phone in landscape.

## Gboard's layout (researched; 9to5Google, Android Police, Android Central)
- The letter rows split down the middle.
- G and V (the middle of the odd rows) are duplicated on both halves; B is not.
- One long spacebar spans the gap.
- Split is the default on a foldable's inner screen, and a setting turns off the duplicated keys.
- Placement of shift, backspace and enter is not documented. Ours stay at the row ends.

## Design
```
 q w e r t           y u i o p
 a s d f g           g h j k l
 ⇧ z x c v           v b n m ⌫
 ?123 😊 [══════ space ══════] . ⏎
```
- **Cut rule (per row):**
  - Applies to rows without the space key.
  - Count the row's CHARACTER keys. An even count splits between the middle two. An odd count
    duplicates the middle one onto both halves (QWERTY gives exactly Gboard's G and V; Russian's
    11-key top row repeats н).
  - Special keys (shift, backspace) and spacers stay on the side they are on.
- **Duplicates off** (Settings switch "Repeat the middle keys on both sides", default **on**): an
  odd row puts the middle key on the left.
- **Equal halves:** in every row, the lighter half's keys are scaled up so both halves have equal
  weight. The gap and both halves then line up across rows.
- **The gap:** an invisible `KeyType.SPACER` key with code 0, sized to **25%** of the row width. It
  draws nothing; tapping in it does nothing (the hit tester returns 0). Swipes still cross it,
  because the gesture surface is the whole grid.
- **Space row:** not cut. Its spacebar's weight grows by the gap's weight, so one bar spans the
  middle.
- **What splits:** letter layouts and the symbol pages. Number pads stay whole, and so does the
  top strip, including the number row there.
- **When it is split:**
  - Docked placement only; floating and one-handed never split.
  - The screen counts as **wide** when `screenWidthDp >= 600`.
  - Two remembered preferences: `splitOnWide` (default **true**) and `splitOnNarrow` (default
    **false**). The Quick access "Split" tile flips the one for the current screen class.
- **Works with:** compact (split applies to the rows compact leaves), the spacebar trackpad,
  swipes across the gap, the long-press symbol fade and multi-finger rollover. The duplicated G and
  V share one key code, so anything that looks a key up by code finds the same definition. That is
  fine, because both copies are the same key. Accessible mode splits too; the gap simply has no key.
- **Behaviour change for existing users (the developer chose this):** after the update, a phone
  turned to landscape (usually 700–900 dp wide) splits by default, as well as unfolded foldables and
  tablets. This is a deliberate departure from Gboard, which only splits by default on a foldable's
  inner screen. The CHANGELOG and README say so, and say that one tap on the Split tile, while in
  landscape, turns it off there for good.
- **The Split tile** (builder's call):
  - Highlighted while the keyboard is split.
  - Tapped while floating or one-handed, it returns to docked and sets split on for the current
    screen class, the way the placement tiles already switch between modes.
  - `splitOnWide`/`splitOnNarrow` live only behind the tile. Settings gets only the "Repeat the
    middle keys" switch.
- **Autocorrect is unchanged** (builder's call). The key-distance table (`KeyboardGeometry`, per
  locale) keeps the unsplit layout, so prediction, the evaluation floors and the English golden
  file are untouched. When split, t/y and g/h sit further apart than the table assumes; on a split
  keyboard that only under-weights a typo that has become less likely anyway.

## Goals / non-goals / anti-goals
- Goals: Gboard's split on wide screens with no setup; one tap to override.
- Non-goals: a resizable gap, a number pad in the gap, fold-hinge detection through Jetpack
  WindowManager (screen width already covers unfolding).
- Anti-goal: a tap landing on a key that is no longer drawn.
  - Key bounds are cached by row and index (`keyBoundsState`).
  - The cache must be cleared whenever the drawn rows change (split on/off, compact,
    layout switch), not merged.

## Approaches
1. **Split the layout data in core with a gap key (picked).** It is pure and testable for every
   layout. Drawing, hit-testing and gestures are unchanged apart from drawing nothing for a spacer.
2. Two side-by-side key grids: rejected. It breaks swipes and glide across the middle and doubles
   the gesture state.
3. Insert the gap only at draw time in Compose: rejected. The per-layout cut rule would be
   untestable.

## Left to the builder
- Names: a `core/layout/SplitLayout` object, its function signatures, the tile icon, and where the
  Settings switch sits (the Layout section, next to the related switches).
- Drawing nothing for a spacer in `KeyCell`/`KeyRowView`, and keeping it out of accessibility
  semantics.

Not device-tested until the developer tries it in landscape (or on the emulator's foldable profile).
