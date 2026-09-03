# Evaluation corpora

Test-only fixtures for `dev.omakey.core.predict.eval`. Not shipped in the APK — these live under
`src/test/resources` and exist purely so engine changes can be measured instead of asserted by
vibes. See `EngineEvaluationTest` for what each one feeds.

## `spell-errors.txt`

`correct<TAB>typo`, one pair per line, 38,038 pairs.

Derived from [Peter Norvig's `spell-errors.txt`](https://norvig.com/ngrams/spell-errors.txt)
(norvig.com/ngrams, MIT-licensed code and freely published data), which aggregates the Birkbeck,
Wikipedia and Aspell misspelling lists. Reduced to one pair per line, lowercased, filtered to
purely alphabetic entries, deduplicated, and with Norvig's `*N` repeat-count suffixes stripped —
each distinct misspelling counts once regardless of how often it occurred in the source.

These are *human* misspellings (cognitive errors: "definately", "recieve"), not touchscreen slips.
That distinction matters: they exercise the lexicon and language model but say nothing about the
spatial model, which is why Phase 3 needs a synthetic tap-noise corpus of its own rather than
reusing this one.

## `sentences.txt`

One lowercased sentence per line, alphabetic words only, minimum 4 words, 1,754 sentences.

Derived from the [Enron Mobile Email Dataset](https://www.keithv.com/software/enronmobile)
(`mobile_vp_lm.txt`), Vertanen & Kristensson, MobileHCI 2011 — genuine email text composed by
Enron employees on BlackBerry devices, manually reviewed and corrected by two humans. Punctuation
tokens (`.period`, `,comma`) and sentence markers were dropped, leaving word sequences.

Chosen deliberately over a book or Wikipedia corpus: it is real text people typed *on a phone*,
which is the register this keyboard actually has to predict. It is also the same dataset the
VelociTap text-entry work evaluated against, so numbers here are comparable to published results.

Used for next-word/completion recall and — reading the words as correctly typed — for the
false-correction rate, the metric users feel most sharply.
