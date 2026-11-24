# Snippets Steps 2–4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:**
- short codes offered in the suggestion bar;
- `{date}`/`{time}`/`{clipboard}` placeholders;
- "Save as snippet" from the keyboard's clipboard panel;
- JSON export and import, with secrets left out.

**Architecture:**
- Every rule is pure, in `core/snippets`, with JVM tests:
  - `SnippetCodes` matches a code and validates it;
  - `SnippetPlaceholders` expands placeholders;
  - `SnippetTransfer` encodes, decodes and plans a merge.
- **The VM** keeps a `snippetOffer` in `KeyboardUiState`. It is computed with each `refreshSuggestions` from the text since the last whitespace. While an offer is up, Space skips autocorrect. The engine is untouched, so the golden file stays unchanged.
- **The strip** draws the offer in front of the word suggestions.

**Spec:** `docs/superpowers/specs/2026-11-23-snippets-design.md` (steps 2–4).

## Global Constraints
- **Codes:** 1–32 characters, no whitespace, unique case-insensitively, plain snippets only.
  - A snippet becoming secret clears its code.
  - Never offered in sensitive fields.
  - Space never expands a code.
- **Placeholders** expand when a plain snippet is typed, never when it's saved, and never for secrets.
  - `{{` types `{` and `}}` types `}`.
  - An unknown `{x}` is typed as written.
  - `{date}` uses `DateFormat.MEDIUM` and `{time}` uses `DateFormat.SHORT`, in the device locale. `{clipboard}` is the current clipboard text, or nothing if there is none.
- **Export format:** `{"format":"omakey-snippets","version":1,"snippets":[{"name","text","shortcut"?}]}`.
  - Secrets are never written.
  - Import uses `ignoreUnknownKeys = true`.
  - Import skips an entry with the same name and text as an existing snippet.
  - A clashing code is imported without its code.
- **`ExtensionContext` gains `fun saveAsSnippet(text: String) {}`**, a default no-op, so existing extensions compile.
- The full gate passes, with zero lint errors. Not device-tested.

## Review Focus
1. A code typed in a password field is never offered. → VM check, `isSensitiveField` (with the own-package rule).
2. Accepting an offer removes exactly the code's characters, even when the code has symbols (`@@`). → Task 1 test on `codeLength`.
3. `{clipboard}` on an empty clipboard types nothing, not "null". → Task 2 test.
4. An import with a malformed file or a wrong format reports an error and writes nothing. → Task 3 test.
5. Changing a code to clash with another one is refused at save. → Task 1 test.

---

### Task 1: Short codes (step 2)
- `Snippet` gets `shortcut: String? = null`. The repository maps it, and `save(..., shortcut: String? = null)` stores it for plain snippets and null for secrets.
- `object SnippetCodes`:
  - `fun tokenBeforeCursor(textBefore: String): String`: the trailing run of non-whitespace.
  - `fun match(textBefore: String, snippets: List<Snippet>): Snippet?`: the plain snippet whose code equals the token, ignoring case. Null for an empty token.
  - `fun validate(code: String, snippets: List<Snippet>, selfId: Long?): String?`:
    - blank is valid and means "no code";
    - whitespace gives "No spaces in a short code";
    - more than 32 characters gives "Up to 32 characters";
    - a clash gives "Already used by <label>".
- **Tests:**
  - token extraction: `"hi @@"`→`"@@"`, `"addr"`, `"x\n"`→`""`
  - case-insensitive match
  - secrets are never matched
  - no match on an empty token
  - each `validate` branch, including saving under its own code
  - the repository round-trips a code, and making a snippet secret clears it
- **VM:**
  - `KeyboardUiState.snippetOffer: SnippetOffer?` (id, label, codeLength).
  - At the end of `refreshSuggestions` (and after `resetForNewField`), compute it from `textEditor.textBeforeCursor(40)`, unless the field is sensitive.
  - `fun acceptSnippetOffer()`:
    - `textEditor.replaceBackward(codeLength, expandedText)`;
    - `pushUndo`, then `resetTypingState()`, then clear the offer.
  - `onSpace`: when an offer is up, skip `maybeAutocorrectBufferedWord`.
- **UI:** in `SuggestionsTabContent`, when `uiState.snippetOffer != null`, draw a leading chip "✂ <label>" that calls `acceptSnippetOffer`.
- **Form:** add a "Short code" field, hidden while Secret is on. The helper text is "Type it, then tap the suggestion. e.g. @@". Validate it with `SnippetCodes.validate`.

### Task 2: Placeholders (step 3)
- `object SnippetPlaceholders { fun expand(text: String, now: java.util.Date, locale: Locale, timeZone: TimeZone, clipboard: () -> String?): String }`.
- **Tests (Locale.US, UTC, a fixed date):**
  - `{date}` gives `"Nov 23, 2026"`.
  - `{time}` matches the formatter's own output for that date. Build the expectation with the same `DateFormat` call, then assert the expansion contains no `{`.
  - `{clipboard}` gives the clipboard text, and an empty string when the clipboard is null.
  - `{{date}}` gives `{date}`.
  - `{nope}` is typed as written.
  - Text with no braces is returned unchanged.
- **VM:** `onSnippetTap` and `acceptSnippetOffer` expand plain snippets through `clipboardText()`. A secret's text is typed exactly as stored.
- **Form:** helper text "Use {date}, {time} or {clipboard} to fill them in when typed.", shown only for plain snippets.

### Task 3: Pin to snippet and export/import (step 4)
- `ExtensionContext.saveAsSnippet(text)`, with a default no-op.
  - The service implements it: open `SnippetsActivity` with `EXTRA_PREFILL_TEXT` on the new form.
  - The clipboard panel's action sheet gets a "Save as snippet" button for text items.
- `object SnippetTransfer`:
  - `fun encode(items: List<Snippet>): String`: plain snippets only; this is the caller's job, and the encoder also drops secrets.
  - `fun decode(json: String): List<Entry>?`: null on a malformed file or the wrong format or version.
  - `fun plan(existing: List<Snippet>, incoming: List<Entry>): Plan`, where `Plan(toAdd: List<Entry>, skipped: Int, codesDropped: Int)`.
- **Tests:**
  - an encode/decode round trip
  - secrets are never encoded
  - malformed input gives null
  - a wrong format string gives null
  - unknown keys are ignored
  - a duplicate is skipped
  - a clashing code is dropped and counted
  - codes inside the incoming file that clash with each other: the first one wins
- **SnippetsActivity list:**
  - **Export:** `CreateDocument("application/json")`, file name `omakey-snippets.json`. It reads each plain snippet's text and writes it. The confirmation says "Exported N snippets. M secret snippets were left out."
  - **Import:** `OpenDocument(["application/json", "text/plain"])`, then decode, plan and save each entry. The result says "Imported N, skipped M duplicates, K short codes already in use."
  - Both are read and written on IO through `contentResolver`.
- **Docs:** CHANGELOG, README and decisions §91. Then run the full gate.
