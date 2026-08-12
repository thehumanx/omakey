# Changelog

All notable changes to Omakey are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/).

## [1.1.1] — 2026-08-12

A round of fixes from real daily-driver feedback on 1.1.0.

### Fixed
- **The keyboard no longer silently learns every word you type.** Previously, finishing any word
  (even a typo that wasn't caught) permanently marked it "known" and immune to correction — the
  only way to teach the dictionary a new word is now the explicit swipe-up "save word" gesture.
- **Correction suggestions no longer disappear when the Autocorrect toggle is off.** That toggle
  now only controls the silent, automatic fix-as-you-type behavior — the suggestion-strip
  corrections you swipe or tap to accept (live mid-word, after a space/punctuation/Enter, and
  cursor-following) are available regardless of the toggle, as they always should have been.
- **A word that would've been auto-corrected but wasn't (toggle off) now still gets offered as a
  fix after the fact** — e.g. typing "hwllo " with autocorrect off now shows "hello" in the strip
  instead of nothing.
- **"Deep" and "Soft" keypress sounds were completely silent.** A bug in how the audio clips were
  trimmed caused the fade-out to zero out the entire clip before it ever played. Re-trimmed and
  verified both actually produce sound.

## [1.1.0] — 2026-08-12

A settings overhaul plus a much smarter autocorrect/prediction system, driven by real on-device
typing feedback.

### Added
- **Settings reorganized into clear sections** (Setup, Appearance, Typing, Sound & Haptics,
  Accessibility, About) instead of one long undifferentiated list.
- **A floating "test your keyboard" button** in Settings opens a full-screen area to try typing
  without leaving the app — warns and offers a one-tap switch if Omakey isn't your active
  keyboard yet.
- **Autocorrect toggle** in Settings.
- **Live correction suggestions** — a typo like "corrcet" now shows "correct" right in the
  suggestion strip while you're still mid-word, not just after you finish it.
- **"Did you mean" corrections for real-word mistakes** — typing "thus" when you meant "this"
  (both real words, just the wrong one) gets offered as a fix based on surrounding context,
  shown after you finish the word (space, punctuation, or Enter) and never auto-applied.
- **Correction now follows your cursor** — tap or navigate back into a word you already typed
  and the suggestion strip still offers a fix for it.
- **Missing-space correction** — "thisis" or "thisbis" gets split back into "this is"
  automatically.
- **Next-word prediction is now optional** (off by default) — a Settings toggle for the
  bigram/history-based "what word comes next" guesses, independent of autocorrect.
- **Suggestion strip shows up to 6 candidates**, not 3.
- **A "Learned words" screen** in Settings — view, search, and remove words your own typing has
  taught the keyboard, individually or all at once.
- **The Enter key is now contextual** — becomes "Go," "Search," "Send," "Next," or "Done" when
  the field asks for one of those instead of always inserting a line break.
- **A second symbols page** (reachable via the `=\<` key) with additional special characters,
  and a directly-tappable comma on the main symbols page.
- **Choice of keypress click sounds**, with a tap-to-preview picker in Settings.
- **Swipe left/right in the emoji panel** to move between categories.

### Fixed
- **A real bug where the bundled dictionary could get silently stuck** at a tiny fraction of its
  full 60,000-word size if the keyboard process was killed mid-seed on first run (which the OS
  does routinely to background IME services) — seeding now correctly resumes instead of
  believing it already finished.
- **Backspacing away an autocorrect fix no longer immediately re-triggers it** on the very next
  word boundary — reverting a correction now actually sticks.
- **Purple/violet accents in Settings replaced** with a neutral grey palette.
- **The Settings title no longer sits under the status bar.**
- **Emoji category tab icons that were invisible** (black-on-dark, every tab except the emoji
  glyph itself) now render in the correct theme color.

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
