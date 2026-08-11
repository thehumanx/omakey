# Changelog

All notable changes to Omakey are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] — 2026-08-12

The first proper release. Omakey went from a from-scratch prototype to a genuinely daily-driver-
ready keyboard: fast gesture typing, real offline autocorrect and next-word prediction, a full
modern emoji picker, and a keyboard that feels tuned rather than just functional.

### Added
- **Real autocorrect.** Typos are now corrected automatically as you type — not just suggested.
  If it gets one wrong, one tap of backspace undoes the correction and restores exactly what you
  typed.
- **Next-word prediction that actually works out of the box.** Previously, word prediction only
  improved the more you personally typed — a fresh install had essentially nothing to offer.
  Now it's pre-loaded with real language data, so predictions are useful from the very first
  sentence you type.
- **A full, modern emoji picker.** Thousands of emoji across every standard category —
  smileys, people, animals, food, travel, activities, objects, symbols, and flags — plus a
  dedicated special-characters picker (°, ™, §, arrows, math symbols, and more).
- **A redesigned suggestions strip.** The bar above your keyboard is now a single swipeable
  space with three pages: word suggestions, quick text tools (select all / copy / cut / paste /
  clipboard history), and a numbers row — swipe between them instead of hunting through menus.
- **A per-key tap preview.** Every key press now briefly shows an enlarged preview of what you
  typed, so you always know you hit the right key.
- **Adjustable haptic and sound feedback**, with a strength slider so it feels right for your
  device and preference.
- **Multi-finger typing support.** Typing fast with overlapping finger taps no longer drops
  keystrokes.

### Changed
- **Tighter key spacing** for faster, more comfortable typing — less wasted space between rows.
- **Rebalanced fonts.** Poppins and Figtree were both adjusted after feedback that one looked
  too heavy and the other too thin — they're properly weighted now, and just labeled "Poppins" /
  "Figtree" instead of exposing internal weight names.
- **Gesture sensitivity is now tunable** in Settings, and swipe-to-delete-word is significantly
  more reliable, especially during fast typing.
- **The numbers row moved** out of the main keyboard into the swipeable suggestions strip, so it
  no longer costs permanent vertical space.

### Fixed
- Haptic feedback that felt like it wasn't working — vibrations were actually firing but too
  short and subtle to notice on most hardware; now properly felt at every strength setting.
- Keypress sound not playing on some devices due to a system-level "touch sounds" setting outside
  the app's control — sound now plays reliably regardless of that setting.
- Dropped keystrokes during fast typing.
- Inconsistent key sizing between letter and symbol keyboard modes.
- Various emoji panel visibility and layout issues.

## [0.2.0] and earlier

Initial from-scratch build: core QWERTY typing, Fleksy-style edge-to-edge gestures (swipe to
delete/space/cycle suggestions), a basic offline dictionary and suggestion engine, an
extension system with clipboard history and emoji panels, theming, and accessibility fallback
for screen readers.
