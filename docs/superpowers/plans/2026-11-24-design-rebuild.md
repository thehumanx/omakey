# Rebuild in the new design language: phased plan

**Source of truth:**
- Figma file "omakey": its Foundations, Components, Keyboard and Settings pages.
- `docs/superpowers/specs/2026-11-24-design-pass.md`: the audit and the decision for each finding.
- `docs/superpowers/specs/2026-11-24-defaults-and-settings-structure.md`: the reworked defaults and the new Settings structure.

Each phase builds, passes the full gate (AGENTS.md) and gets a fresh review before the next one starts. Touch and layout work isn't proven by tests: every phase ends with what to try on the phone.

## Phase 0: before starting (needs the developer)
- **Commit what's built so far.** That's compact mode, punctuation, Select All, split and snippets, on `feature/compact-mode`. The rebuild touches nearly every UI file, and it shouldn't be tangled with unreviewed feature work.
- **Get the Figtree font files** (SIL Open Font License) from Google Fonts. Bundle them under `app/src/main/res/font/` with the OFL text.
  - This means downloading files, which needs the developer's OK.

## Phase 1: foundations in code
- **`core/ui` (or `app/ui/design`):**
  - **Tokens:** colour roles for Light and Dark, the spacing scale and the three radii, mirroring the Figma variables.
  - **Typography:** Title 22/28, Row 16/22, Body 14/20, Label 13/18, Caption 12/16, Button 16/20, all in Figtree.
- **Shared composables, one per Figma component:**
  - `OmakeySwitch`, `OmakeyButton` (Primary/Tonal/Destructive)
  - `IconTile`, `SettingsRow` (Toggle/Nav/Value), `SettingsCard` with inset dividers, `SectionLabel`
  - `Segmented`, `OmakeyField`, `ConfirmSheet`, `Toast`
  - `DragReorderList`: grip, lift and shadow, haptics, and TalkBack move actions
- **Icons:** new Phosphor-style vectors for the emoji categories, the space bar and hash.
- **Tests:** token values, plus the reorder logic (pure functions in core).

## Phase 2: Settings
- **Home:** the new structure (Look / Typing / Above the keys / More), a status card, and search.
- **Pages:**
  - Theme, Keys, Font, Size & position
  - Corrections & suggestions, Gestures, Languages
  - Bar pages, Snippets, Clipboard, Emoji
  - Sound & vibration, Privacy, Accessibility, About
- **Dialogs and overlays:** every AlertDialog becomes a ConfirmSheet. The overlays (learned words, clipboard history, size & position) become pages with a back arrow.
- **Copy:** one line per row, verbs on buttons (`SettingsIndex` titles and keywords updated together).
- Search keeps working: `SettingsIndexTest` is updated with the new structure.

## Phase 3: keyboard surfaces
- **Keys:** radius 10 everywhere, the existing `KeyInsets`, and Figtree as the default keyboard font on fresh installs.
- **Bar:**
  - suggestion cells with the active-word underline
  - snippet chips (plain and lock)
  - the ink offer chip
  - page dots
- **Panels:**
  - **Quick access:** a 2×4 grid of tiles plus a header with a close button.
  - **Language picker:** code badges.
  - **Emoji panel:** stroke-icon tabs and a 44 dp bar.
  - **Clipboard:** cards with a pin icon, an empty state, and a bottom action sheet.
- **Overlays:**
  - the Select all pill and finger circle
  - toast instead of the banner
  - floating keyboard with radius 24 and shadow
  - one-handed gutter tiles
  - resize scrim and a Done button
  - number-pad space icon
  - symbol cells at radius 10

## Phase 4: defaults
- New defaults apply to fresh installs only: theme Auto, key backgrounds on, home-row stripe off, capitals off, prediction on, auto-capitalize on, double-space full stop on, swipe right for space on, vibration 0.35.
- A stored value always wins. A test checks each pref loader returns the stored value when one exists, so updating users keep their choices.

## Phase 5: release prep
- Update CHANGELOG and README (with new screenshots once on a device).
- Bump the version.
- Developer device test across Light/Dark, Normal/Grid, docked/floating/one-handed/split, and TalkBack.
