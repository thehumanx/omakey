# Fresh-install defaults and Settings structure (proposal)

Part of the design pass (2026-11-24). The current values come from a full read of every `*Preferences` class. In every class, the default in the data class matches the default in its loader.

## Defaults: what changes and why
The principle is that a fresh install should feel finished: matching the phone, typing like a modern keyboard, and familiar to someone arriving from Fleksy (most users). Nothing that touches privacy changes.

| Setting | Today | Proposed | Why |
|---|---|---|---|
| Theme | Dark | **Auto (follow the phone)** | A dark keyboard on a light phone is the first thing that looks wrong. |
| Key backgrounds | off | **on** | Flat labels on a flat ground read as unfinished to most people. Fleksy fans can turn it off in one tap, and the setting sits near the top. |
| Home row highlight | on | **off** | A tinted stripe is decoration that competes with the keys. It's still there for people who use it. |
| Always show capitals | on | **off** | Lowercase keys show the Shift state at a glance. It stays one toggle away for Fleksy habits. |
| Next-word prediction | off | **on** | An empty suggestion bar after a space makes the keyboard look idle. Every major keyboard ships this on. |
| Auto-capitalize | off | **on** | People expect "i went" to become "I went" and sentences to start with a capital. Off reads as broken. |
| Double-tap space for a full stop | off | **on** | It's a standard expectation on both iOS and Gboard. |
| Swipe right for space | off | **on** | It's Fleksy's core gesture, and most users come from Fleksy. Compact mode already relies on it. |
| Vibration strength | 0.5 | **0.35** | A lighter tick feels precise. 0.5 is a buzz on most motors. |
| Everything else | unchanged | unchanged | Clipboard history, learning, update checks and autofill stay on, as today. Sounds stay off. Split on wide screens and the number pad stay as they are. |

**Migration rule:** only fresh installs get the new defaults. A stored value always wins, so nobody's keyboard changes under them after an update. Most prefs already fall back to the default only when a key is missing. Check each one before building.

## Settings structure: from 9 pages to 6 groups
Today's pages (Appearance, Keyboard, Languages, Typing, Gestures, Privacy, Sound, Accessibility, About) split related things. For example, key look is under Appearance but key popups are under Buttons, and Snippets and Clipboard sit in two different places.

The proposed home has one status card, then six groups:
1. **Look**
   - **Theme:** presets, your themes, the editor.
   - **Keys:** layout style, key backgrounds, capitals, secondary symbols, key press popup.
   - **Font.**
   - **Size & position.**
2. **Typing**
   - **Corrections & suggestions:** autocorrect, prediction, capitals, punctuation.
   - **Gestures.**
   - **Languages.**
3. **Above the keys**
   - **Bar pages:** drag to reorder, password manager logins.
   - **Snippets.**
   - **Clipboard.**
   - **Emoji.**
4. **Keys & pads**
   - Keys beside the spacebar.
   - Symbols and long-press symbols.
   - Number pad.
5. **Sound & vibration.**
6. **Privacy & about**
   - Learning and learned words.
   - What leaves your phone.
   - Accessibility.
   - Updates.
   - Version.

Search stays at the top of the home page. Every page has a back arrow, a 22 pt title, and section labels in sentence case.
