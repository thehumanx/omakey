# Keyboard feature comparison

A survey of what mainstream and open-source Android keyboards offer, done to spot gaps in
Omakey. Non-technical, user-facing feature comparison — implementation notes for anything
picked up from here belong in `AGENTS.md`, not this file.

## Comparison table

| Feature | Gboard | SwiftKey | AnySoftKeyboard | OpenBoard | FlorisBoard | Fleksy (legacy) | **Omakey** |
|---|---|---|---|---|---|---|---|
| Glide/swipe-to-type | Yes | Yes | Yes | No | In progress (rebuilding) | No (gesture shortcuts instead) | No |
| Fleksy-style surface-wide gesture shortcuts (delete word, space, cycle suggestions) | No | No | No | No | No | Yes | **Yes** |
| Offline dictionary/prediction | Yes (+cloud) | Yes (+cloud) | Yes | Yes | Yes | Yes | **Yes** |
| Cloud-assisted prediction | Yes | Yes | No | No | No | Unknown | No (offline-by-default, deliberate) |
| Clipboard manager | Yes | Yes | No | No | Yes (Smartbar) | Yes | **Yes** |
| Number row toggle / dedicated numbers access | Yes (row) | Yes (row) | Yes (row) | Yes (row) | Yes (row) | Unknown | **Yes (swipeable Numbers tab, not a fixed row)** |
| One-handed mode | Yes | Yes | No | No | Yes | Unknown | No |
| Split keyboard for tablets | Yes | Yes | No | No | No | Unknown | No |
| Resizable keyboard height | Yes | Yes | No | Limited | Yes | Unknown | **Yes** |
| Multilingual / multi-layout switching | Yes | Yes | Yes (layout packs) | Yes | Yes | Unknown | No (English QWERTY only) |
| Voice input | Yes | Yes | Yes | No | Smartbar entry point (delegates to system) | Unknown | No |
| GIF / sticker search | Yes | Yes | No | No | No | Unknown | Stubbed, unregistered (needs INTERNET) |
| Custom themes / theme editor | Yes (limited) | Yes | Yes (rich) | Basic | Yes (rich) | Yes | **Yes (theme editor + presets)** |
| Per-app spacebar language/app indicator | Yes | Yes | No | No | No | Unknown | No |
| Cursor-control gesture (spacebar drag to move cursor) | Yes | Yes | No | No | No | Unknown | No |
| Text expansion / snippets | Yes | Yes | No | No | No | Unknown | No |
| Incognito / no-learning mode | Yes | Yes | No | No | Yes (Smartbar toggle) | Unknown | No |
| Extension/plugin system for panels | No (closed) | No (closed) | No | No | No | Unknown | **Yes (in-process, clipboard + emoji built in)** |
| Gesture-typing-free keyboard-only workflow (no swipe-to-type) | N/A | N/A | N/A | Yes (only option) | Partial | Yes (by design) | **Yes (by design, Fleksy-inspired)** |
| Accessibility / TalkBack fallback mode | Yes | Yes | Partial | Partial | Partial | Unknown | **Yes (auto-detected + manual override)** |
| Custom keypress sound | Yes (system) | Yes (system) | Yes | Limited | Limited | Unknown | **Yes (bundled, bypasses system touch-sound setting)** |
| No `INTERNET` permission at all | No | No | Depends on build | Yes | Yes (core) | Unknown | **Yes** |

Sources: [MakeUseOf — open-source Gboard alternatives tested](https://www.makeuseof.com/best-open-source-gboard-alternatives-tested/), [How-To Geek — open-source Android keyboards that rival Gboard](https://www.howtogeek.com/open-source-android-keyboards-that-rival-gboard/), [FlorisBoard GitHub discussions](https://github.com/florisboard/florisboard/discussions/1190), [AnySoftKeyboard vs. OpenBoard — SourceForge](https://sourceforge.net/software/compare/AnySoftKeyboard-vs-OpenBoard-Keyboard/), plus general product knowledge of Gboard/SwiftKey's current shipping feature sets.

## Gap list for Omakey, prioritized

Cross-referenced against `AGENTS.md` §16 (Open work) so this doesn't duplicate what's already
tracked there as a known gap.

**High value, plausible near-term:**
1. **One-handed mode** — shrinks and docks the keyboard to one side; every mainstream keyboard
   and FlorisBoard have it, no open-source keyboard we found lacks it except OpenBoard/AnySoftKeyboard. Straightforward layout-transform on top of the existing resizable-height system (`LayoutPreferences`).
2. **Incognito / no-learning mode** — a per-session toggle that stops `AutocorrectIndex.learn()`
   and `PredictionEngine.recordAcceptedWord()` from writing anything, for typing in sensitive
   fields. Small, self-contained change given the existing `*Preferences` pattern.
3. **Cursor-control gesture (spacebar drag)** — Gboard/SwiftKey's long-press-and-drag-the-spacebar-to-move-cursor. Fits naturally into the existing `GestureStateMachine` as a new gesture type scoped to the space key.

**Medium value, more work:**
4. **Multilingual / multi-layout switching** — Omakey is English-QWERTY-only today; even a second
   bundled layout (e.g. Spanish) plus a layout-switch key would close a real gap versus every
   competitor. Layout system (`Layouts.kt`) is already data-driven for exactly this.
5. **Text expansion / snippets** — user-defined short-string-to-long-string replacements, offline,
   no privacy tradeoff. Natural fit for the existing extension system as a new built-in.
6. **Voice input entry point** — Omakey doesn't need to implement speech recognition itself; a
   mic key that hands off to the system's `RecognizerIntent`/voice IME (same pattern FlorisBoard's
   Smartbar uses) would be low-effort and high-visibility.

**Lower priority / larger investment:**
7. **Glide/swipe-to-type** — explicitly deferred per `AGENTS.md` §1.3 (needs a neural decoder);
   still the single largest feature gap versus Gboard/SwiftKey/AnySoftKeyboard, but a multi-week
   investment, not a quick win.
8. **Split keyboard for tablets** — only Gboard/SwiftKey have this; low priority unless Omakey
   targets tablet users specifically.
9. **Per-app spacebar language/app indicator** — cosmetic, low priority.
10. **On-device ML prediction** — already tracked as punted in `AGENTS.md` §6/§16, not
    re-litigated here.
