<div align="center">

# Omakey

**The keyboard for people who type like they mean it.**

Fast. Gesture-driven. Fully offline. Nothing you type ever leaves your phone.

</div>

---

## The pitch

Remember Fleksy — the keyboard that made typing feel like a sport instead of a chore? Omakey
picks up where it left off. Every gesture is a shortcut, every correction is instant, and every
byte of what you type stays on your device, permanently.

Most keyboards today ship with an ad SDK, a cloud sync service, an analytics pipeline, and a
"smart" prediction engine that quietly phones home. Omakey ships with none of that. It doesn't
ask for internet access — it can't send your keystrokes anywhere even if it wanted to, because
the permission to do so was never requested. What you get instead is a keyboard that's
obsessively tuned for one thing: getting words out of your head and onto the screen as fast as
your thumbs can move.

## Who it's for

- People who used to love Fleksy and have been settling for something worse ever since.
- Anyone tired of keyboards that correct with a shrug instead of confidence.
- Privacy-conscious users who want a keyboard that can't leak what they type, because it
  physically has no way to.
- One-handed and thumb typists who want the keyboard to come to them, not the other way around.

## What Omakey is not

Being upfront about scope matters more than a longer feature list:

- **Not a glide/swipe-to-type keyboard.** Omakey's gestures are *action* shortcuts (delete a
  word, insert a space, cycle a suggestion) — you still tap out each letter. If you're looking
  for trace-the-word gesture typing across the whole layout, that's not what this is.
  All of Omakey's gestures are documented below so you know exactly what they *do* cover.
- **English (US QWERTY) only, for now.** No other layouts or languages are bundled yet.
- **Not on the Play Store yet.** You'll need to sideload it or build from source (instructions
  below) — see [Status](#status) for what that means in practice.
- **No cloud backup or cross-device sync.** Your learned words, clipboard history, and settings
  live only on the one device you're using — by design, not as a missing feature. There's
  nothing to sync because there's no server to sync to.
- **No GIF search.** The extension slot exists in the code for a future GIF picker, but it's not
  wired up yet — it would need internet access, which Omakey deliberately doesn't request today.

## Everything Omakey actually does

### Typing that keeps up with you

- Full QWERTY layout with shift, caps lock, two pages of symbols, and long-press accent
  characters (à, é, ñ, and more — hold a key like `e` or `a` to see its variants).
- The Enter key adapts to context — it becomes "Go," "Search," "Send," "Next," or "Done"
  depending on what field you're typing into, instead of always being a plain return key.
- Every key press shows a small preview bubble above your finger, so you always know what
  landed.
- Tuned to keep up with fast, sloppy typing — built to handle fingers that overlap mid-word
  without falling behind.
- Adjustable keyboard height and position in one combined screen: drag a handle to resize, drag a
  grip to raise the keyboard off the bottom edge for easier one-handed reach — capped so it can
  never be dragged past the middle of the screen, so you can always see what you're typing into.

### Gestures — the whole point

This is the section to actually read. Every gesture below works directly on the keyboard surface,
no menus required:

| Gesture | What it does |
|---|---|
| **Swipe left** on any key | Deletes the entire last word, not just one character. |
| **Swipe right** on any key | Inserts a space (optional — off by default, one tap to enable in Settings). |
| **Swipe up** on the suggestion strip | Accepts the current suggestion, or — if you swipe up on a word you typed yourself — saves it to your personal dictionary so it's never flagged as a typo again. Swipe up a second time on an already-learned word to un-learn it. Either way you'll see a quick confirmation flash in the strip. |
| **Swipe down** on the suggestion strip | Cycles backward through alternatives, the mirror of swipe up. |
| **Swipe up/down repeatedly** | Genuinely cycles through every alternative, back and forth, as many times as you want — whether the word is still being typed, just finished, or you've tapped the cursor back into something you wrote a minute ago. |
| **Tap and hold Shift** | Locks in all-caps typing (caps lock). A quick tap instead capitalizes just the next letter, then releases — the standard mobile-keyboard convention, done properly. |
| **Long-press and drag the spacebar** | Moves the text cursor left or right without needing to tap precisely inside your text — great for fixing a typo three words back without losing your place. |
| **Long-press a letter key** with accent variants | Pops up alternate characters (à, á, â, ä, etc.) to pick from. |
| **Swipe left/right in the emoji panel** | Switches between emoji categories with a smooth directional slide. |
| Adjustable swipe sensitivity | A Settings slider tunes how far a swipe needs to travel before it's recognized — shorter for snappier gesture response, longer if taps are getting misread as swipes. |

### Smart suggestions and autocorrect, done honestly

- Real autocorrect — typos get fixed the moment you finish a word, not just offered as a
  suggestion you have to notice and tap. Got corrected wrong? One tap of backspace undoes it
  immediately, and it won't just re-correct the same word right back.
- Catches typos that need two fixes at once (like a swapped letter pair *and* a wrong character),
  not just single-letter slips.
- Catches "real-word" mistakes too — typing "thus" when you meant "this" (a real word, just the
  wrong one) gets quietly offered as a fix based on the words around it, without ever silently
  auto-applying something that risky on its own.
- The suggestion strip offers alternatives for a word *even when what you typed is already
  valid* — typing "well" correctly still offers "we'll" and other close real words, since only
  you know which one you actually meant.
- See the fix *before* you finish typing — a typo like "corrcet" shows "correct" right in the
  suggestion strip while you're still mid-word.
- Missing a space between two words ("thisis") gets split back into "this is" automatically.
- Full contraction support (im → I'm, weve → we've, shoudve → should've, and dozens more), with
  fuzzy matching so a typo of a contraction still resolves correctly.
- Optional next-word prediction (off by default, one tap to turn on) — guesses what comes next
  based on common usage and your own typing history.
- Optional auto-capitalize (off by default) — capitalizes the start of a new field and after
  sentence-ending punctuation, if you turn it on.
- A "Learned words" screen in Settings shows every word your own typing has taught the keyboard —
  view, search, edit, or remove any of them individually, or clear all of them at once.
- An inline calculator — type `12+7=` and the result shows up right in the suggestion strip,
  ready to tap in.

### Undo, redo, and text tools — always one tab away

Swipe to the Tools tab in the suggestion strip and you get, grouped clearly:

- **Undo / Redo** for your last several typed or deleted words — made a correction you didn't
  want, or deleted more than you meant to? Undo brings it right back, redo re-applies it.
- **Select all / Copy / Cut / Paste** — full text-selection tools without leaving the keyboard.
- **Clipboard history** — every recent copy (text *and* images) is saved and one tap away.
  Long-press any item to delete it. Opening clipboard mode dims everything else in the toolbar so
  it's clearly its own space; tap the clipboard icon again to get back to typing.

### A full, modern emoji picker

- Thousands of emoji across every standard category — smileys, people, animals, food, travel,
  activities, objects, symbols, and flags.
- A dedicated emoticon/kaomoji category — `(^_^)`, `ヽ(´▽\`)/`, and dozens more text-based faces,
  sized and spaced properly instead of being squeezed into the same grid as single-character
  emoji.
- A special-characters picker for things like °, ™, §, arrows, and math symbols.
- Smooth directional transitions switching categories or leaving the panel — slide, not fade.

### Make it feel like yours

- Several built-in themes (Light, Dark, Follow-system, Accent) — Light, Dark, and Follow-system
  keep a clean, neutral spacebar by default; turn on "Pick accent color from system" if you want
  the spacebar (and press states) to pull color from your device's own wallpaper-based palette
  instead.
- A full custom theme builder with an HSV color picker — pick your own background, key, and
  accent colors and Omakey derives the rest.
- Adjustable font for the keys.
- A home-row highlight to help you find your place by feel, without looking.
- A consistent icon set for Shift, Backspace, and every Enter state — dimmed by default, lighting
  up the moment you press it.

### Feedback that feels right

- Adjustable haptic feedback with a strength slider.
- An optional keypress sound, with a choice of click styles you can preview before picking, plus
  its own independent volume slider.

### Accessible by default

- Automatic fallback for TalkBack users — when screen-reader touch exploration is on, Omakey
  switches to standard tap-to-type instead of gesture capture, so nothing gets in the way of
  accessibility tools.

## Privacy, for real

Omakey's manifest does not request the `INTERNET` permission. This isn't a settings toggle or a
promise in a privacy policy — the app literally has no code path capable of sending a network
request. Nothing you type, copy, or teach the keyboard can leave your device, because there's no
mechanism for it to travel anywhere. Your personal dictionary, clipboard history, and every
setting live in local storage only.

## Status

Omakey is on release 2.0.0. Core typing, gestures, autocorrect, predictive text, themes, the
clipboard manager, and the emoji panel are all working today and getting refined constantly — see
[CHANGELOG.md](CHANGELOG.md) for the full history of what's shipped in each release.

## Getting started

Omakey isn't on the Play Store yet. In the meantime:

1. Grab the latest APK from the [Releases page](https://github.com/thehumanx/omakey/releases), or
   build it yourself from source with Gradle.
2. Sideload the APK (you'll need to allow installs from your file manager or browser the first
   time).
3. Open your phone's **Settings → System → Languages & input → On-screen keyboard**, and enable
   Omakey.
4. Switch to it from the keyboard-switcher icon on your current keyboard, or from that same
   Settings screen.

## License

License information coming soon.

---

<div align="center">

Built for people who miss typing fast.

</div>
