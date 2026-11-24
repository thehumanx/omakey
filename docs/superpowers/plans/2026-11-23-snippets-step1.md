# Snippets Step 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Snippets page in the bar above the keys (tap to type, long-press to edit, "+" to add), managed in a non-exported activity, with secret snippets encrypted by the Android Keystore and hidden while the phone is locked.

**Architecture:**
- **Pure rules** live in `core/snippets` with JVM tests: labels, visibility, validation, and the password heuristic.
- **Storage** is a Room table (database v7) behind `SnippetRepository`, which takes the DAO and a `SecretCipher` interface. The repository is tested on the JVM with fakes; the Keystore implementation and the migration need a device.
- **The keyboard** gets `TopStripTab.SNIPPETS`. Typing a snippet reuses `extensionHost.insertText`, the clipboard panel's path: one batch, one undo step, no autocorrect, no learning.
- **Editing** happens in a new non-exported `SnippetsActivity`.

**Tech Stack:** Kotlin, Jetpack Compose, Room (KSP), AndroidKeyStore AES-GCM, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-23-snippets-design.md` (Step 1 and Storage).

## Global Constraints
- JDK 17. Lint must pass with zero errors. minSdk is 26.
- **Database:**
  - version 6 → 7, with `MIGRATION_6_7` appended to `MIGRATIONS`;
  - commit `core/schemas/dev.omakey.core.db.OmakeyDatabase/7.json`;
  - add an androidTest case;
  - never `fallbackToDestructiveMigration`.
- **Keystore:** alias `omakey_snippets_v1`, AES-256-GCM, `setUserAuthenticationRequired(false)`.
- **Secret text** is never written to disk, logs, `toString()`, the clipboard or exports.
- **Snippet text** never reaches autocorrect, learning or suggestion history: insert it only through `extensionHost.insertText`.
- **Limits:** text 1–2,000 characters (validated after `trim()`, stored as typed). The name is required for a secret.
- **Copy:**
  - The empty row reads "+ Add a snippet (email, phone, address…)".
  - The password prompt reads "This looks like a password. Make it secret?" with the buttons [Make secret] and [Keep plain].
  - A failed decrypt reads "Can't unlock, re-enter".
- `SnippetsActivity` has `android:exported="false"`.
- Short codes and placeholders are **not** in step 1. The `shortcut` column exists but has no UI yet.
- Every `Json` keeps `ignoreUnknownKeys = true` (none is added in step 1).
- Touch, IME window and Keystore behaviour are not device-tested; say so.

## Review Focus
1. **A secret's plain text reaching disk or memory dumps:** the entity has no plain text for a secret, and `Snippet.toString()` never prints text. → Task 2 test.
2. **Phone locked:** no secret chip shows, and tapping cannot type one even through a stale chip. → Task 1 rule, and Task 3 re-checks at tap time.
3. **Decrypt failure** (a missing key): the snippet opens its edit form, with no crash and no blank text typed. → Task 2 test (null result).
4. **Reordering and deleting** keep `position` contiguous, and the keyboard row follows. → Task 2 test.
5. **Upgrade from v6** keeps every word and clip. → the androidTest case (needs a device; say not run).

---

### Task 1: Pure snippet rules

**Files:**
- Create `core/src/main/kotlin/dev/omakey/core/snippets/Snippet.kt`
- Test `core/src/test/kotlin/dev/omakey/core/snippets/SnippetRulesTest.kt`

**Interfaces (Produces):**
- `data class Snippet(val id: Long, val name: String, val text: String?, val secret: Boolean, val position: Int)`
  - `text` is null for a secret until it is decrypted.
  - `toString()` is overridden to print only the id, name, secret flag and position.
- `object SnippetRules`:
  - `const val MAX_TEXT = 2000`, `const val MASK = "••••"`
  - `fun chipLabel(s: Snippet): String`
    - A secret gives `"$MASK ${s.name}"`.
    - A plain snippet gives its name if it isn't blank. Otherwise it gives the text's first line, cut to 24 characters, with "…" added when cut.
  - `fun visible(all: List<Snippet>, deviceLocked: Boolean): List<Snippet>` drops secrets while locked and keeps the order.
  - `fun validate(name: String, text: String, secret: Boolean): String?` returns an error message, or null when valid.
    - Blank text gives "Add the text to type".
    - More than 2,000 characters gives "Up to 2000 characters".
    - A secret with a blank name gives "Name this secret so you can find it".
  - `fun looksLikePassword(text: String): Boolean` follows the spec's rule (one token, 8–64 characters, no space or `@`, at least 3 of the 4 character classes).

- [ ] **Failing tests:**
  - chip label: secret / named / unnamed long / unnamed multi-line
  - `visible` hides secrets only while locked and keeps the order
  - each `validate` branch
  - `looksLikePassword`:
    - true: `Tr0ub4dor&3`, `hunter2!X`
    - false:
      - `bibek@example.com`
      - `+977 9800000000`
      - `password` (only one character class)
      - `Ab1!` (too short)
      - `hello world`
  - `toString` omits the text
- [ ] **Run** `./gradlew :core:testDebugUnitTest --tests '*SnippetRulesTest*'`. Expect it to fail, then implement, then expect it to pass.

### Task 2: Storage, cipher and repository

**Files:**
- Modify `core/.../db/Entities.kt`, `Daos.kt` and `OmakeyDatabase.kt` (v7, `MIGRATION_6_7`, `entities += SnippetEntity`, `fun snippetDao()`).
- Create:
  - `core/.../snippets/SecretCipher.kt` (an interface, plus `KeystoreSecretCipher`)
  - `core/.../snippets/SnippetRepository.kt`
- Test:
  - `core/src/test/.../snippets/SnippetRepositoryTest.kt`, using a `FakeSnippetDao` and a `FakeCipher` (for example, a reversible byte transform with a switch that makes it fail)
  - `core/src/androidTest/.../OmakeyDatabaseMigrationTest.kt` gets `migration_6_to_7_keeps_everything_and_adds_snippets`

**Interfaces (Produces):**
- `@Entity(tableName = "snippets") data class SnippetEntity`:
  - `@PrimaryKey(autoGenerate = true) id: Long = 0`
  - `name: String = ""`
  - `text: String?`
  - `secretCipher: ByteArray?`
  - `secretIv: ByteArray?`
  - `shortcut: String? = null`
  - `position: Int`
  - `createdAt: Long`
  - `updatedAt: Long`
  - Override `equals`/`hashCode` for the arrays, and a `toString` with no content.
- `MIGRATION_6_7`: `CREATE TABLE IF NOT EXISTS snippets (...)` matching Room's generated `7.json` exactly. Take it from the schema after building.
- `@Dao interface SnippetDao`:
  - `@Query("SELECT * FROM snippets ORDER BY position") fun observeAll(): Flow<List<SnippetEntity>>`
  - `suspend fun all(): List<SnippetEntity>`
  - `suspend fun findById(id: Long): SnippetEntity?`
  - `@Insert suspend fun insert(e: SnippetEntity): Long`
  - `@Update suspend fun update(e: SnippetEntity)`
  - `@Query("DELETE FROM snippets WHERE id = :id") suspend fun delete(id: Long)`
  - `@Update suspend fun updateAll(list: List<SnippetEntity>)`
- `interface SecretCipher`:
  - `fun encrypt(plain: String): Sealed?`
  - `fun decrypt(sealed: Sealed): String?`
  - `data class Sealed(val iv: ByteArray, val bytes: ByteArray)`
  - Implementations return null on any failure and never throw.
- `class KeystoreSecretCipher : SecretCipher` uses `AndroidKeyStore`, `KeyGenParameterSpec` with `PURPOSE_ENCRYPT or PURPOSE_DECRYPT`, `BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`, a 256-bit key, and `Cipher.getInstance("AES/GCM/NoPadding")` with a 128-bit tag. The key is generated lazily.
- `class SnippetRepository(private val dao: SnippetDao, private val cipher: SecretCipher, private val clock: () -> Long = System::currentTimeMillis)`:
  - `val snippets: Flow<List<Snippet>>`: secrets map to `text = null`.
  - `suspend fun save(id: Long?, name: String, text: String, secret: Boolean): Boolean`
    - A null id appends at position = count.
    - Saving a secret stores `text = null` and sealed bytes. Saving it as plain clears the cipher columns.
    - Returns false when encryption fails.
  - `suspend fun textToType(id: Long): String?`: the plain text, or the decrypted secret, or null.
  - `suspend fun delete(id: Long)`: deletes, then renumbers positions 0..n-1.
  - `suspend fun move(id: Long, by: Int)`: swaps with a neighbour, clamped, and renumbers.

- [ ] **Failing repository tests:**
  - a plain round trip
  - a secret is stored with a null `text` and with bytes that don't contain the plain text's UTF-8
  - `textToType` decrypts
  - a failing cipher gives `save` → false and nothing stored
  - a decrypt failure gives `textToType` → null
  - delete renumbers
  - moving the first item up is a no-op; moving it down swaps
  - switching from secret to plain clears the cipher columns
- [ ] Run, expect fail, implement, expect pass. Build once so Room writes `7.json`, then copy its `createSql` into `MIGRATION_6_7`.
- [ ] Add the androidTest case:
  - create a v6 database from `6.json`;
  - migrate;
  - assert the words and clips are unchanged;
  - insert and read a snippet.

  It compiles under `:core:compileDebugAndroidTestKotlin`; **running it needs a device.**

### Task 3: The keyboard's Snippets page

**Files:**
- `app/.../keyboard/KeyboardUiState.kt`: `TopStripTab.SNIPPETS`.
- `TopStripTabPreferences.kt`: `PAGE_ORDER = listOf(SUGGESTIONS, SNIPPETS, NUMBERS, TOOLS)`.
- `KeyboardViewModel.kt`:
  - A constructor parameter `snippetRepository: SnippetRepository?` (default null).
  - `val deviceLocked = MutableStateFlow(false)` with `fun setDeviceLocked(b: Boolean)`.
  - `val snippetChips: StateFlow<List<Snippet>>`, built from `combine(repo.snippets, deviceLocked) { s, l -> SnippetRules.visible(s, l) }`.
  - `fun onSnippetTap(s: Snippet)`:
    - Re-check that it's not a secret while locked.
    - Get `repo.textToType(s.id)` on IO.
    - A non-null result goes to `extensionHost.insertText(it)`.
    - Null calls `openSnippetEditor(s.id)`.
  - The callbacks `openSnippetEditor: (Long?) -> Unit` are set by the service.
- `KeyboardTopStrip.kt`: a `TopStripTab.SNIPPETS -> SnippetsTabContent(...)` branch.
  - A horizontally scrolling row of chips styled like the suggestion chips.
  - Each chip has `combinedClickable(onClick = tap, onLongClick = edit)`, and a trailing "+" chip that calls `openSnippetEditor(null)`.
  - The empty state is a single chip with the empty-row copy.
  - Semantics: "Snippet, <name>" and "Secret snippet, <name>".
  - Feedback is `onKeyPress`.
- `OmakeyInputMethodService.kt`:
  - Build `SnippetRepository(database.snippetDao(), KeystoreSecretCipher())` and pass it in.
  - `openSnippetEditor(id)` starts `SnippetsActivity` with `FLAG_ACTIVITY_NEW_TASK` and the extra `EXTRA_SNIPPET_ID` (-1 = new). Like `openSettings`, but the target is not exported.
  - On `onStartInputView`, call `setDeviceLocked(keyguardManager.isDeviceLocked)`.
  - Register an `ACTION_USER_PRESENT` receiver that calls `setDeviceLocked(false)`, and unregister it in `onDestroy`.
- `TopStripSettings` tests, if any (none found), plus the Settings page list for the bar's pages: add "Snippets" where the pages can be hidden.
  - Find where `setPageShown` is called in Settings and add the toggle row with the same pattern and copy: "Snippets: your saved text, a tap away."
- [ ] Implement, then compile. There is no JVM test for the IME (it's not unit-testable). The chip label and visibility rules are already pinned by Task 1.

### Task 4: `SnippetsActivity`, the Settings entry point, docs and the gate

**Files:**
- Create `app/.../settings/SnippetsActivity.kt`: a `ComponentActivity` with a Compose UI using the Settings theme helpers.
  - **List mode** (no extra): rows with each snippet's chip label, ↑/↓ reorder buttons, a tap to edit, and an "Add snippet" button.
  - **Edit mode** (`EXTRA_SNIPPET_ID`):
    - Name and Text fields.
    - The Secret switch. While it's on, the Text field is masked with an eye toggle.
    - Save, and Delete behind a confirmation dialog.
    - Opening a secret loads its text with `textToType` only when the eye is toggled, or when the user edits. On a decrypt failure, show "Can't unlock, re-enter" and leave the text empty.
    - Save runs `SnippetRules.validate`, then, if `!secret && looksLikePassword(text)`, the prompt dialog.
  - The activity builds its own `SnippetRepository` from `OmakeyDatabase.getInstance` and `KeystoreSecretCipher`.
  - Set `window.setFlags(FLAG_SECURE)` while a secret's text is visible.
- `AndroidManifest.xml`: `<activity android:name=".settings.SnippetsActivity" android:exported="false" android:theme=(same as Settings) />`.
- `SettingsIndex.kt`:
  - `SettingsSectionKey.SNIPPETS("Snippets", SettingsPage.KEYBOARD)`
  - `SettingKey.SNIPPETS_MANAGE("Snippets", SNIPPETS, listOf("snippet", "hotkey", "quick input", "email", "phone", "password", "address"))`
- `SettingsActivity.kt`: a `SNIPPETS ->` branch with a `ClickableSettingRow` ("Your saved email, phone, address or logins, a tap away in the bar above the keys.") that starts `SnippetsActivity`.
- **Docs:**
  - CHANGELOG Added: **Snippets**, Fleksy-style quick input.
  - README bullet.
  - `docs/internal/decisions.md` §90.
- [ ] Run the full gate (the five commands in AGENTS.md) plus `./gradlew :core:compileDebugAndroidTestKotlin`. Expect success and zero lint errors.
