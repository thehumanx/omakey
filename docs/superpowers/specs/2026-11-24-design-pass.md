# Design pass: audit and decisions

The developer asked for a thorough design pass across the keyboard and Settings: "minimal but premium and crafted, functional and aesthetic, every decision conscious". Mocks are on the design canvas (omakey reorder design). This file pairs what exists today (from a code inventory, 2026-11-24) with the decision that replaces it, so building can follow it screen by screen.

## The system (Foundations / Components boards)
- **Type:** Inter in Settings and omakey's own panels.
  - Settings sizes: Title 22/28 600, Row 16/22 500, Body 14/20, Label 13/18 600 (sentence case).
  - Keyboard sizes: key 23, strip 16.
  - No other sizes.
- **Colour:** neutral ground, surface, tonal and ink, plus one accent (#2F5FF5 light / #7C9CFF dark) for the single selected or active thing. Muted text #5E5E5E (6.3:1). The keyboard keeps following the user's theme.
- **Spacing:** a 4 dp grid (4, 8, 12, 16, 24, 32, 48). Screen gutter 16; rows at least 64 tall; touch targets at least 48.
- **Radii, three only:** 10 for keys and chips, 14 for buttons, icon tiles and fields, 24 for cards and sheets. Full for pills and switches.
- **Icons:** Phosphor regular everywhere, at 20 dp, in 40 dp tonal tiles in Settings. No text glyphs as icons (↑ ↓ ✂ ⌨ ‹ › + ␣ •) and no emoji as icons (category tabs, 📌, 📋, 😊, 🌐).
- **Components:**
  - **Grouped row card:** dividers inset to the text, no divider before helper text.
  - **Switch:** ink track, 52×32.
  - **Segmented:** a sliding white thumb on a tonal track.
  - **Buttons:** one ink button per screen; tonal for the rest; quiet red text for destructive actions.
  - **Fields:** label above, ink focus ring.
  - **Empty state:** an icon tile, a sentence saying what the feature is for, and one action.
  - **Sheets:** bottom sheets with verb buttons replace AlertDialog.
  - **Toast:** an ink pill with an optional action.
  - **Drag to reorder:** grip, lift with shadow, dashed slot, haptics, and TalkBack move actions.

## Audit → decision, by screen
| Today | Decision |
|---|---|
| Keys 8 dp radius; quick access tiles 10; symbol cells 10; clipboard sheet 12; snippet chips 16 | Everything on the keyboard 10 (keys, tiles, cells, chips); sheets 24 |
| "?123"/"ABC" text beside Phosphor keys | Keep "?123"/"ABC" as text (a label, not an icon), weight 500, 15 sp; icons only where there is no word |
| Number pad space "␣" | Phosphor space-bar icon |
| Select All pill: ALL CAPS, 160×44 | "Select all", sentence case, 15/600, with a select-all icon; 48 tall, 24 radius |
| Snippet offer "✂ label" | Phosphor snippet icon plus the label, ink chip (not accent) |
| Snippets page: "+" text chip; 16 dp radius; long empty text | Outline icon button with a plus icon; chips at radius 10; empty: one chip "Save your email, phone…" opening the form |
| Autofill pager "‹" "›" | Phosphor caret icons |
| Quick access: 12 sp title at 0.7, no close, 10 sp labels | Title 13/600 with a close icon; 2×4 tiles at radius 14 with 12 sp labels; active tile ink with an accent dot |
| Language picker: the same globe on every tile | A two-letter code badge per language (EN, NE) |
| Emoji tabs are emoji; bar 40 dp; "ABC" text | Phosphor category icons; bar 44 dp |
| Clipboard: bare rows, 📌, plain empty text, Material buttons on a 12 dp card | Rows on tonal cards at radius 14, pin icon, empty state component, bottom sheet with icon rows (Pin, Save as snippet, Remove) |
| Extension header ⌨ glyph, 36 dp | 44 dp header with a keyboard icon |
| Floating keyboard: no radius or shadow | 24 radius, soft shadow, grip in the 44 dp header |
| One-handed gutter: 18 dp icons, 10 radius | 20 dp icons in 40 tiles at radius 14 |
| Resize: no scrim; "Done" pill 20 radius | Dim the keys 40 %; Done is an ink button at radius 14 |
| Banner covers the strip, plain text | Ink toast pill above the keys with a fade, with an optional Undo |
| Settings: default Material Switch, Segmented, FilterChip, AlertDialog, Buttons; TextButtons for row actions | All replaced by the components above; row actions go to a trailing icon button or an overflow sheet |
| Section title in primary 14 sp; a divider after every child | Label 13/600 muted; dividers only between rows |
| Setup card with hardcoded green and amber and Material buttons | A single status card: "omakey is your keyboard ✓" or one ink button for the next step, using a check-circle icon (no raw colour) |
| Unselected choice tiles invisible (same colour as the card) | Tonal tiles; selected is an ink ring |
| Overlays: titleMedium plus text "Done/Close/Cancel" | Every screen has a back arrow and a Title 22; Save/Done is the ink button at the bottom |
| Search empty state with AssistChips | Recent and suggested searches as plain rows with a search icon |
| Theme editor: unrounded hue bar with no indicator | Rounded hue track with a thumb; swatch rows |
| Language rows with flag emoji | Code badges (EN, NE); size and licence on the second line |
| Snippets list: text Back, ↑/↓ glyphs, no secret marker | Back arrow, cards with a grip, lock icon for passwords, shortcut shown as a key cap, add as a primary button |
| Walls of text ("What leaves your phone", install dialog, size overlay help) | One sentence each; details behind "Learn more" rows |

## Copy rules
- Verbs on buttons ("Save snippet", "Delete", "Keep it"); never "OK".
- Sentence case everywhere. No "…" except for real truncation.
- One line of description per row at most; if it needs two, it needs a page.
- Say what happens to the user, not how it works ("omakey can't see the passwords").
