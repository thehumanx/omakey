# Snippets

## Intent
Most omakey users come from Fleksy. A Fleksy user asked for Fleksy's quick-input bar: their
email, phone number and login details, a tap away. The developer wants it **exactly like Fleksy,
and better wherever possible**, borrowing the best ideas from other keyboards. Research:
`docs/internal/snippets-research.md`.

## What other keyboards do (summary)
- **Fleksy Hotkeys:** a row of 7 one-tap keys above the keyboard, holding anything, passwords
  included.
- **Gboard, Samsung and iOS:** a short code that offers the expansion in the suggestion bar.
- **SwiftKey:** a pinned clip can be given a code.
- **Texpand and similar expanders:** `{date}`/`{time}` placeholders.
- **No keyboard documents protecting saved passwords.**

## Decisions the developer made
- **Passwords are allowed** (Fleksy users saved them). They are protected as **secret snippets**:
  - encrypted at rest;
  - shown as `••••`, labelled by name;
  - left out of export, backup and short-code suggestions;
  - typed with **one tap**, with the phone's own lock screen as the protection. No fingerprint
    prompt per use.
- **Built in four steps, designed together.** Each step ships on its own.

## The four steps
### Step 1: the snippets row, managing snippets, and secrets
- **A Snippets page** in the swipeable bar above the keys (`TopStripTab.SNIPPETS`):
  - Placed after Suggestions in `PAGE_ORDER`.
  - It can be hidden like the other pages (the hidden set is stored, so existing users see the
    new page).
  - It is a horizontally scrolling row of chips, one per snippet, in the user's order. A chip
    shows the snippet's **name**, or for a plain snippet with no name, its text cut to one line.
  - **Tap** types the snippet. **Long-press** opens that snippet's edit form. A **"+"** chip at
    the end opens the add form.
  - With no snippets, the row shows "+ Add a snippet (email, phone, address…)".
- **Add and edit forms** live in Settings, in a new Snippets section (reached from the row and from
  Settings search). Each form has:
  - Name: optional for plain snippets, required for secret ones.
  - Text: multi-line, up to 2,000 characters.
  - A "Secret" switch.
  - Short code: optional, used from step 2 on; hidden or disabled while Secret is on.
  - Delete, behind a confirmation.
  - Reordering by dragging is in the Settings list; the keyboard row follows that order.
- **Typing a snippet:**
  - It goes through `TextEditor` (the only `InputConnection` user) as one commit.
  - It **bypasses autocorrect, word learning and suggestion history**. Snippet text, secret or
    not, must never enter the personal dictionary or the next-word model.
  - It also does not enter the clipboard.
- **Secret snippets:**
  - The text is encrypted with an AES-256-GCM key held in the **Android Keystore** (alias
    `omakey_snippets_v1`, no user-auth requirement, generated on first use). The ciphertext and IV
    are stored; the plain text never touches disk or logs.
  - If decryption fails (key gone, for example after a factory reset of the keystore), the chip
    shows "Can't unlock, re-enter" and opens the edit form with the text empty.
  - The chip shows `••••` plus the name.
  - Secrets are shown and typed in every field, password fields included (that is the point).
  - In the edit form the text is masked, with an eye toggle.
- **While the phone is locked** (`KeyguardManager.isDeviceLocked`), for example when replying
  from a lock-screen notification, **secret chips are not shown**. This is the builder's call; it
  follows from the developer's choice that the phone's lock is what protects secrets. Plain chips
  still show. The row re-reads the lock state each time the keyboard opens and when the screen is
  unlocked (`ACTION_USER_PRESENT`).
- **The edit and add forms open only from omakey itself** (builder's call). They live in a
  **non-exported** activity, so no other app can send an `Intent` that opens a secret's form.
  `SettingsActivity` stays exported for the launcher, and its Snippets section starts that internal
  activity.
- **Plain snippets in password fields:** shown as usual. Fleksy parity, and the developer chose not
  to block.
- **A password-looking plain snippet** prompts once on save: "This looks like a password. Make it
  secret?" [Make secret] [Keep plain]. It never blocks. "Looks like" means:
  - one token, 8–64 characters;
  - no spaces and no `@`;
  - at least 3 of these 4 classes: lowercase, uppercase, digit, symbol.
- **Incognito** does not hide snippets. Incognito stops learning and capturing; it doesn't stop
  typing things the user saved.
- **TalkBack:** chips announce "Snippet, <name>". Secrets announce "Secret snippet, <name>" and
  never the text.

### Step 2: short codes
- A plain snippet can have a short code: 1–32 characters with no spaces, unique and
  case-insensitive (for example `@@`, `addr`, `sig`).
- **"Being typed" means the text since the last whitespace before the cursor** (builder's call).
  This is a separate check for codes, so symbol codes like `@@` work. Word tracking
  (`wordBeforeCursor`, `isWordChar`) is unchanged, and so is `golden/en_us.txt`.
- **Autocorrect leaves an exact code alone** (builder's call). When that token equals a code,
  Space does not autocorrect it, the way a saved word is protected. With no codes defined, nothing
  changes, which the golden test proves.
- When the text being typed **equals** a code, the suggestion bar's first slot offers the snippet,
  showing its name or a one-line preview with a snippet icon. **Tapping** replaces the code with
  the snippet's text.
- **Space never expands a code on its own**, as in Gboard: no surprises, and a code that is also a
  real word stays harmless.
- Never offered for secret snippets or in sensitive fields (`SensitiveField`). It follows the
  incognito rules as suggestions do (it is shown, but nothing is learned).

### Step 3: placeholders
- `{date}` gives the locale's medium date. `{time}` gives the locale's short time. `{clipboard}`
  gives the newest text on the system clipboard, or nothing if there is none.
- `{{` and `}}` type literal braces. An unknown `{word}` is typed as written.
- Placeholders expand when the snippet is typed, never when it is saved.
- **Secret snippets never expand placeholders;** their text is typed exactly as written.

### Step 4: pin to snippet, and export/import
- **Clipboard history** gets a "Save as snippet" action that opens the add form prefilled. Clips
  marked sensitive were never captured, so they can't get there.
- **Export** writes a JSON file through the system file picker. Its shape is
  `{ "format": "omakey-snippets", "version": 1, "snippets": [...] }`. **Secret snippets are left
  out**, and the screen says so with the count.
- **Import** reads that format with `ignoreUnknownKeys = true`. It skips entries whose name and
  text match an existing snippet. A clashing short code is imported without the code, and the
  result says how many.

## Storage
- A new Room entity `SnippetEntity`, table `snippets`, **database version 6 → 7**:
  - `MIGRATION_6_7` creates the table;
  - the exported schema `7.json` is committed;
  - a case is added to `OmakeyDatabaseMigrationTest`;
  - never `fallbackToDestructiveMigration`.
- Columns:
  - `id` INTEGER PK autoincrement
  - `name` TEXT NOT NULL DEFAULT ''
  - `text` TEXT (null when secret)
  - `secretCipher` BLOB (null when plain)
  - `secretIv` BLOB
  - `shortcut` TEXT (null; a unique index on `lower(shortcut)` is enforced in code)
  - `position` INTEGER NOT NULL
  - `createdAt` and `updatedAt` INTEGER NOT NULL
- `allowBackup="false"` already keeps the database out of cloud and adb backups.

## Goals / non-goals / anti-goals
- **Goals:**
  - Fleksy's one-tap row, unlimited instead of 7.
  - Gboard-style codes.
  - Placeholders.
  - Pinning a clip to a snippet.
  - Portable export.
  - Passwords kept safely.
- **Non-goals:**
  - Cloud sync.
  - Folders or categories.
  - Rich text or images.
  - Fingerprint per use (rejected by the developer).
  - Reading iOS or other keyboards' formats.
- **Anti-goals:**
  - Snippet text leaking into the dictionary, suggestions, clipboard history or logs.
  - A secret's plain text on disk or in an export.
  - Losing snippets on an upgrade.

## Approaches
1. **A dedicated `snippets` table with an encrypted column for secrets (picked).** Clean, easy to
   reorder, edit and export, and protected like the learned-words table.
2. Reusing `clipboard_history` with a snippet flag: rejected. It mixes in transient clips, the
   clear and trim operations put snippets at risk, and the sensitive-clip rule conflicts with
   saving secrets on purpose.
3. A JSON file outside Room: rejected. It loses migrations, safe concurrent writes from Settings
   and the IME, and the learned-data protections.

## Left to the builder
- The class names past `SnippetEntity`/`TopStripTab.SNIPPETS`, and the snippet icon.
- The exact chip styling (use the existing suggestion-chip look and theme colours).
- The Settings form layout, and how the IME opens it (an `Intent` to the Settings activity with
  the snippet id).

Not device-tested until the developer tries it.
