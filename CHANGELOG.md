# Changelog

All notable changes to omakey are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/).

## [2.3.0] — 2026-08-16

A new keyboard layout style, plus a large batch of autocorrect, gesture, and emoji-panel fixes.

### Added
- **Grid layout mode** — a new "Layout style" toggle in Settings → Appearance, independent of
  which color theme is picked. Every key becomes a bordered, edge-to-edge cell, and a pressed key
  fills its whole cell solid instead of just dimming, for a plain spreadsheet-like look. Works
  with Light, Dark, Follow-system, Accent, and your own custom themes.
- **Grid mode's border is fully customizable** — a dedicated color (defaults to a legible
  dark/light grey depending on the theme) and a thickness preset (Small/Medium/Large), both
  editable from the theme editor.
- **Custom themes now remember which layout mode they were designed for** and the Settings theme
  list only shows the ones relevant to whichever mode is currently active — a theme tuned while
  looking at Normal mode often has Grid-specific fields nobody ever actually chose, and vice
  versa. Themes made before this existed still show up in both, untagged.
- **A "Recent" emoji category**, always shown first, tracking your most-recently-used emoji so you
  don't have to hunt for the same one repeatedly.
- **A "Show key press popup" setting** — turn off the brief enlarged-letter bubble that appears
  above your finger on every tap, independent of the separate long-press accent/punctuation
  popup.

### Changed
- **The suggestion strip now clearly shows which candidate is about to be typed** while you cycle
  through alternatives with swipe up/down — the active one renders at full brightness, the rest
  fade, instead of every candidate looking equally "selected."
- **Autocorrect no longer suggests broken word-splits for obvious typos** — "helko" now correctly
  suggests "hello" (not "he lo"), "thid" now suggests "this" (not "th id"). Splitting a run-together
  phrase like "thisis" into "this is" still works correctly when that's genuinely the best fix.
- **Swipe-left delete treats a trailing emoji or punctuation mark as its own unit** — e.g.
  "hahaha😂" now deletes the emoji on one swipe and the word on the next, instead of both at once.
- **Swipe-up save now learns the right word even with the cursor sitting after a trailing space**
  (e.g. "bibek |") — it used to silently do nothing in that specific case.
- **The learn/unlearn confirmation ("word learned") now shows up regardless of which extension bar
  tab or panel is open**, not just the suggestion strip.
- Settings reorganized: **Theme is now split into Presets** (a simple Light/Dark/Follow
  system/Accent toggle) **and Custom themes** (filtered by layout mode, see above). **Layout style
  moved to the top of Appearance**, since it decides how the rest of that section applies. **"Key
  backgrounds" and "Home row highlight" moved from Typing to Appearance.** "Key preview popup"
  renamed to **"Long press for special characters"** for clarity against the new tap-popup
  setting. Checkmarks replaced with clearer selected-state styling (segmented toggles, highlighted
  borders) throughout, and spacing between related settings rows increased for readability.

### Fixed
- **Fast typing could still occasionally transpose two letters** when a second finger landed on
  the next key before the first one lifted — commit order now always matches press order.
- **The suggestion/tools/numbers bar could go completely invisible** in some states — a real
  regression, now fixed.
- **Emoji panel**: the Recent category's transition no longer looks like a glitchy "expand" instead
  of a clean slide; the category tab row now scrolls to keep the active category visible when you
  swipe past off-screen ones; deleting all your text via the emoji panel's own backspace button no
  longer leaves stale suggestions sitting in the strip; emoji now sit centered in their grid cells.
- A long tail of Grid-mode visual consistency fixes — border thickness, missing borders, background
  fills, and padding across the suggestion strip, tools row, number row, emoji panel, and extension
  tabs, so every screen of the keyboard renders consistently in Grid mode.

## [2.1.2] — 2026-08-13

### Fixed
- **Fast typing dropping or scrambling letters** (e.g. "this" registering as "thsi", or a space
  landing before the last letter of a word) — a per-keystroke spelling-correction scan was
  running on the main thread, competing with the touch input pipeline under fast typing. It now
  runs in the background, off the typing hot path.
- **Autocorrect missing obvious fixes** like "autocorrecr" → "autocorrect" — a handful of common
  words (autocorrect, emoji, selfie, hashtag, youtube, whatsapp, instagram) weren't in the
  dictionary at all. Added, and existing installs now actually pick up dictionary updates like
  this one instead of staying frozen at whatever shipped on first install.
- **Caps lock's highlight color looked like plain grey on custom themes** — now uses the same
  accent color already used for other "active" key states, so it reads clearly regardless of
  theme.
- **The delete-word shimmer animation could replay itself when switching between the letter and
  symbol keyboards**, even with no delete in between.
- **Typing an emoji then hitting backspace showed a "?" instead of deleting it** — most emoji are
  encoded as a pair of characters internally; backspace was only removing half of the pair.
- **Long-pressing a key near the right edge of the keyboard (P, L, M) couldn't reach every accent/
  symbol option** — there was no physical screen room to drag further right. Dragging now works
  in either direction from any key.
- Theme editor: the color picker wasn't actually centered (a real layout bug, not just a style
  miss), and its hex code field could render with invisible text and a squeezed-looking box.

### Changed
- **The delete-word shimmer animation is faster** and now noticeably brighter on dark themes,
  where it used to be nearly invisible.
- **Long-press special-character picker redesigned**: instead of a small popup above the held
  key, the whole keyboard now fades into a full-width symbol-picking mode — drag anywhere to
  browse, release to select, and it fades back to the letter keyboard automatically.
- **Theme editor overhauled**: the live keyboard preview now shows the full keyboard (not just
  one row) and stays on screen the whole time you're editing, instead of scrolling away. The 4
  color pickers moved from a stacked list into a swipeable, one-at-a-time carousel with a page-dot
  indicator. The color picker also gained a hex code field (type a color code directly, or copy
  the current one) alongside the visual picker.

## [2.1.0] — 2026-08-13

### Fixed
- **The Enter key's contextual action (Go/Search/Send/etc.) actually fires now** — e.g. tapping
  the arrow in a browser's URL bar, or the search icon after typing a query, previously did
  nothing at all even though the key's icon correctly showed what it should do.
- **Suggestions for the word before your cursor now show up right after a delete** — e.g. typing
  "okay i wont do", deleting "do", and swiping down to fix "wont" now works immediately, instead
  of the suggestion strip staying empty until you typed something else.
- **Undo/redo is more reliable** — fixed cases where undoing could drop punctuation or insert an
  extra space, and single backspaces (not just whole-word swipe-deletes) are now undoable too.
- **Long-press popups now work on every letter, not just vowels** — holding a consonant like "z"
  previously did nothing at all.
- **The swipe-left delete sound now follows your tap-volume setting** — it used to sit at its own
  fixed volume, out of step with the slider.

### Added
- **A distinct "swoosh" sound** for swipe-left word deletion, instead of reusing the ordinary key
  tap sound.
- **A shimmer animation** along the home row's top and bottom edges when you swipe left to delete
  a word.
- **A "Always show capital letters" setting** (on by default, matching omakey's existing look) —
  turn it off for the more familiar keyboard behavior where keys show lowercase letters until you
  press Shift.
- **Long-press a key to drag-select accents and punctuation** — holding a key now pops up its
  accent/punctuation variants right above it; without lifting your finger, drag over to the one
  you want and let go to type it. Every letter key now has something to pop up, not just the
  vowels — mirrored from the symbols keyboard's own layout (e.g. holding "z" shows "*", the same
  position it sits at on the symbols page). Dragging past a key's own popup reveals a few extra
  everyday symbols, fading in as a subtle cue that you've moved into a different tier.
- **A new app icon.**

### Changed
- **Brand name is now styled all-lowercase ("omakey")** everywhere it's shown — app name, Settings
  screens, and this changelog/README included.

## [2.0.0] — 2026-08-13

A full rethink of the suggestion strip and correction engine, plus gestures, theming, the
extension bar, the emoji panel, and the settings screens — enough ground covered since 1.1.1 to
call this the 2.0 release.

### Added
- **The suggestion strip now offers alternatives for a word even when it's already valid** — e.g.
  typing "well" (correctly) still offers "we'll" and other close real words to swipe/tap to,
  since only you can tell which one was actually meant.
- **Swipe up/down now genuinely cycles** through alternatives in either direction, however many
  times, whether the word is still being typed, just finished, or the cursor is sitting inside an
  already-committed one after a tap — not a one-shot "accept the fix" anymore.
- **Real edit-distance-2 correction** — typos needing two fixes (like a swapped pair *and* a
  wrong letter, e.g. "keynaord" → "keyboard") are now caught, not just single-character typos.
- **Contraction suggestions** ("well" → "we'll", plus the existing "im"/"weve"/etc. set) work
  correctly now regardless of whether the plain form happens to already be a "known" word.
- **Contraction coverage significantly expanded** (he'd/he'll, she'd/she'll, it'd/it'll,
  there's/there'd, here's, who'll/who'd, that'd/that'll, should've/could've/would've/might've/
  must've, mightn't/oughtn't, ain't, y'all, o'clock, and more) plus **fuzzy matching** for typos
  of a contraction itself (e.g. "shoudve" → "should've"), and a fix for short contraction forms
  like "im"/"id" that were being silently dropped before contraction lookup ever ran.
- **A "Keyboard position" setting** — drag the keyboard up or down to raise it off the bottom edge
  for easier one-handed thumb reach, capped so it can never be dragged past the middle of the
  screen.
- **A consistent Phosphor-style filled icon set** for Shift, Backspace, and every Enter state
  (not just "Done" — Go, Search, Send, Next, Previous, and the plain return key all get their own
  icon now) — styled after Fleksy: dimmed by default, lighting up to full brightness the moment
  you actually press it.
- **Caps lock** — long-press (hold) shift to lock in all-caps typing, distinct from a plain tap's
  one-shot "capitalize just the next letter." The shift icon and key background both change while
  it's engaged so it's obvious it's on; tapping shift again turns it fully off.
- **Swipe right for space** — off by default — a Settings toggle under Typing → Gestures.
- **Icons for the Tools tab** (select all / copy / cut / paste / clipboard history) replacing
  plain text labels, same Phosphor icon family as the rest of the keyboard.
- **Version number** now shown at the bottom of the About section in Settings.

### Changed
- **Numbers page now comes before Tools** in the suggestion strip's swipeable pages (Suggestions →
  Numbers → Tools).
- **Number keys now match the size/font/padding of every other key** — previously noticeably
  smaller and differently spaced.
- **Swiping between suggestion-strip pages (Suggestions/Numbers/Tools) needs a much shorter swipe**
  to commit — previously required a near-full-width flick.
- **Removed the page-position dots** from the suggestion strip.
- **Light and Dark themes now use the same spacebar accent color** — they used to differ, which
  read as inconsistent rather than intentional.

### Added (theming)
- **"Follow system" theme option** — a new entry in the theme picker, alongside Light/Dark/Accent,
  that switches the keyboard between Light and Dark automatically to match your device's system
  setting, live.
- **"Pick accent color from system"** toggle (Android 12+ only) — pulls the spacebar's accent
  color from your device's actual Material You palette instead of the theme's own fixed color.
- **Build your own theme** — a new "Create your own theme" entry in the theme picker opens a
  full HSV color picker for the 4 colors that matter most (background, key color, home-row
  stripe, spacebar), with a live preview. Save as many as you like, switch between them like any
  other theme, and edit or delete them later.
- **Long-press-and-drag the spacebar to move the cursor** — hold the spacebar, then drag left or
  right to move the cursor one character at a time, without needing to tap precisely in the text.
- **A simple inline calculator** — type an expression like "12+7=" and the result ("19") shows up
  in the suggestion strip, ready to swipe or tap in. Handles +, -, *, / with standard order of
  operations; never applies itself automatically.
- **Undo/redo** — two new buttons in the Tools tab undo or redo your last few typed or deleted
  words.
- **A tidier Settings page** — divider lines now separate individual settings within each
  section, and rows like "Learned words" and "Keyboard position" are fully tappable instead of
  needing to hit a small button on the right.
- **A sound volume slider** in Settings, independent of which click sound is selected.

### Removed
- **The redundant dismiss ("⌄") strip** beneath the key grid — the system's own back button/
  gesture already hides the keyboard, so this was duplicate and just ate vertical space.

### Fixed
- **A real bug where "thisis" corrected to "thesis" instead of splitting into "this is"** —
  "thisis" is also one edit away from the real word "thesis," and the old engine tried single-word
  fixes before ever attempting a split. Splits are now tried first.
- **Swipe-up-to-save now saves what you actually typed**, not whatever alternative happened to be
  displayed after cycling through a few options.
- **Swipe up was silently replacing the typed word with the first suggestion instead of saving
  it** — a real bug in the swipe-up handler where "not cycling yet" was wrongly treated the same
  as "advance to the first suggestion" instead of "keep what I typed."
- **The alternatives search no longer suggests obscure/rare words** it happened to find nearby.
- Swipe-up-to-save skips words that are already in the dictionary instead of redundantly re-saving them.
- **Swipe-left-to-delete-word could fire instead of the spacebar cursor-drag gesture** on a fast
  left-drag starting on the spacebar — the two gestures raced, and delete-word sometimes won.
  Swipe-left is now never recognized when it starts on the spacebar.
- **Swipe-up to save/learn a word only worked while the word was still being typed** — the far more
  common case (a word you'd already finished, or navigated back into) silently did nothing. Swipe
  up now works uniformly, and swiping up a second time on a word you saved un-learns it again —
  either way, a brief "`word` learnt"/"`word` unlearnt" confirmation flashes in the suggestion
  strip.
- **Selecting all text and then swiping left or backspacing now deletes the whole selection**,
  instead of only removing one word/character next to the cursor and leaving the rest selected.
- **The clipboard-history listener no longer runs for the keyboard's entire lifetime** — it's now
  only active while the keyboard is actually visible, so it doesn't read (and trigger Android's
  "app read your clipboard" notification for) clipboard changes made in other apps while omakey
  isn't on screen.
- **The suggestion strip now remembers which page (Suggestions/Numbers/Tools) you last had open**,
  across app restarts, instead of always resetting to Suggestions.
- **The Tools tab is now visually grouped** — Undo/Redo, then Select all/Copy/Cut/Paste, then
  Clipboard, separated by dividers instead of one flat row of icons.
- **A redesigned clipboard-manager mode** — opening it keeps the familiar top strip in place
  (rather than swapping in a separate header), dims every icon except Clipboard, and disables
  swiping between pages while it's open. Tapping Clipboard again closes it.
- **Copied images now show up in the clipboard manager**, not just text.
- **Long-press a clipboard item to remove it.**
- **Fixed invisible emoji/special-character keys** — the emoji grid had no explicit text color at
  all, so every glyph rendered in the platform default (black), invisible on dark themes.
- **The emoji panel's backspace key now matches the alphabet keyboard's exactly** — same icon,
  same dimmed-by-default/lit-on-press treatment, instead of a plain "⌫" glyph.
- **Smooth transitions** — switching emoji categories, and switching between the normal keyboard
  and the emoji/clipboard panels, now cross-fades instead of swapping instantly.
- **A dedicated emoticons/kaomoji category** — "(^_^)" style text faces, separate from unicode
  emoji.
- **Fixed the keyboard visibly shifting width when switching between letters and symbols** — the
  symbols pages were missing the extra key slot letters mode has for the emoji shortcut. That
  slot now holds a Settings shortcut on the symbols pages instead of being left empty.
- **The Numbers page in the suggestion strip shows extra special characters while you're on a
  symbols page**, instead of repeating the same digits already on the main grid.
- **Keyboard height is now the same full-screen drag-to-resize experience as Keyboard position**,
  instead of an always-visible inline editor taking up space in the Typing section.
- **"Pick accent color from system" no longer overrides your own custom themes** — it only ever
  applies to Light, Dark, Follow system, and Accent.
- **The Setup section now shows a checkmark or exclamation mark** next to each step, so you can
  tell at a glance whether omakey is enabled and set as your active keyboard.
- **Learned words can now be edited**, not just removed.
- **The Keyboard height drag handle now sits above the preview and is clearly visible**, instead
  of a barely-visible bar pushed down near the bottom of the screen — and dragging it up now
  correctly grows the keyboard (was backwards).
- **"Keyboard height" and "Keyboard position" are now one combined "Keyboard size & position"
  screen** — drag the handle to resize, drag the grip inside the preview to reposition, both on
  the same live preview instead of two separate screens.
- **Back button/gesture on the Keyboard position/height, theme editor, learned words, and test-
  typing screens now returns to Settings**, instead of exiting the app entirely.
- **Emoticons/kaomoji now fit their own width** instead of being squeezed into the same fixed
  8-column grid as single-character emoji — adaptive columns and a smaller font stop long kaomoji
  strings from wrapping awkwardly.
- **A new "Auto-capitalize" setting** (off by default) — capitalizes the first letter of a new
  field and after sentence-ending punctuation (`.`/`!`/`?`).
- **Fixed the backspace key shifting position when switching between letters and symbols mode** —
  the symbols pages' bottom row had one more key than the letters layout's equivalent row, so its
  total width didn't match, visibly nudging every key after the first special key.
- **Switching between the keyboard and the emoji/clipboard panel now slides up/down**, and
  swiping between emoji categories now slides left/right, instead of a directionless cross-fade.
- **Copying/cutting text no longer shows a second "read your clipboard" toast** on top of the
  system's own "Copied to clipboard" one — omakey already knows what it just copied, so it no
  longer needs to read the clipboard back to record it in history. (Fixed for real this time — the
  first attempt suppressed the read but missed that the underlying listener could end up
  registered twice, which independently re-triggered the same toast.)
- **Light, Dark, and Follow-system themes now use a neutral spacebar** — it blends in with the
  rest of the keys unless you turn on "Pick accent color from system," instead of always showing
  a blue accent whether you asked for it or not.

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
  without leaving the app — warns and offers a one-tap switch if omakey isn't your active
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

The first proper release. omakey went from a from-scratch prototype to a genuinely daily-driver-
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
