#!/usr/bin/env python3
"""Builds the language model for a downloadable language pack (AGENTS.md §66 Phases 6, 8, 9).

English is built by `build_lm.py`, which this reuses but deliberately does not change: English's
behaviour is pinned by the golden tests, and every language added here would otherwise be a chance
to disturb it.

Same design as English — a curated vocabulary (Hunspell ∪ words attested in proofread text), web or
subtitle unigram counts blended with conversational unigrams, bigrams and trigrams from Tatoeba —
driven by a per-language `Lang` config instead of module constants. Like `build_lm.py`, it refuses to
write a model that fails its language's probes.

It also holds out every HELDOUT_MODULO-th Tatoeba sentence from training and writes those to
`core/src/test/resources/eval/<code>/sentences.txt`, so the evaluation harness measures the model on
text it has never seen.

Usage:
    scripts/build_lang_lm.py es_ES --out build/packs/es_ES/lm.bin
"""
from __future__ import annotations

import argparse
import bz2
import re
import sys
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).parent))
import build_lm  # noqa: E402
from build_lm import ValidationError, fetch, log, to_probabilities  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
HELDOUT_MODULO = 50
HELDOUT_LIMIT = 3000
PUNCTUATION = ".,!?;:\"()[]{}¿¡«»…—–-“”„‘"


@dataclass
class Lang:
    code: str
    name: str
    #: Lowercase letters a word may contain.
    letters: str
    #: Running text for n-grams: a Tatoeba sentence export, or (where Tatoeba has too little, as for
    #: Nepali) a Wikipedia pages-articles dump.
    tatoeba: str | None
    hunspell: tuple[str, str]
    #: "word count" per line (FrequencyWords format), or None to use Tatoeba unigrams alone.
    frequency_list: str | None
    probes: Callable[[dict], None]
    sources: list[dict]
    #: Whether words may contain an internal apostrophe ("aujourd'hui").
    apostrophes: bool = False
    #: Elided forms split off as their own token ("l'homme" → "l'", "homme"), identically to the
    #: keyboard's runtime tokeniser (see `LanguageProfile` / the French pack's profile).
    clitics: tuple[str, ...] = ()
    min_evidence: int = 5
    weight_frequency_unigram: float = 0.5
    weight_conversational_unigram: float = 0.5
    vocab: int = 150_000
    wikipedia: str | None = None
    word_re: re.Pattern = field(init=False)

    def __post_init__(self):
        body = f"[{re.escape(self.letters)}]+"
        self.word_re = re.compile(f"^{body}(?:'{body})*$" if self.apostrophes else f"^{body}$")

    def accept(self, word: str) -> bool:
        return bool(word) and len(word) <= build_lm.MAX_WORD_LEN and (word in self.clitics or bool(self.word_re.match(word)))

    def tokens(self, text: str) -> list[str]:
        """Lowercase NFC tokens of [text], clitics split off, non-words dropped."""
        out = []
        for raw in unicodedata.normalize("NFC", text).lower().replace("’", "'").split():
            token = raw.strip(PUNCTUATION)
            for clitic in self.clitics:
                if token.startswith(clitic) and len(token) > len(clitic):
                    out.append(clitic)
                    token = token[len(clitic):]
                    break
            if self.accept(token):
                out.append(token)
        return out


def strip_accents(word: str) -> str:
    """[word] with combining marks removed ("también" → "tambien", "niño" → "nino")."""
    return "".join(c for c in unicodedata.normalize("NFD", word) if not unicodedata.combining(c))


def read_frequency_list(path: Path, lang: Lang, verbose: bool) -> Counter:
    counts: Counter = Counter()
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            for token in lang.tokens(parts[0]):
                counts[token] += int(parts[1])
    log(verbose, f"        {len(counts):,} frequency-list unigrams")
    return counts


def read_tatoeba(path: Path, lang: Lang, verbose: bool):
    """Unigram/bigram/trigram counts plus held-out sentences. Held out by sentence id, so the split
    is stable across rebuilds and across Tatoeba export dates."""
    unigrams, bigrams, trigrams = Counter(), Counter(), Counter()
    heldout: list[list[str]] = []
    sentences = 0
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or not parts[0].isdigit():
                continue
            words = lang.tokens(parts[2])
            if not words:
                continue
            if int(parts[0]) % HELDOUT_MODULO == 0:
                heldout.append(words)
                continue
            sentences += 1
            unigrams.update(words)
            bigrams.update(zip(words, words[1:]))
            trigrams.update(zip(words, words[1:], words[2:]))
    log(verbose, f"        {sentences:,} sentences -> {len(unigrams):,} uni / {len(bigrams):,} bi / "
                 f"{len(trigrams):,} tri; {len(heldout):,} held out")
    return unigrams, bigrams, trigrams, heldout


WIKI_DROP = [
    re.compile(r"<ref[^>]*/>"), re.compile(r"<ref[^>]*>.*?</ref>", re.S), re.compile(r"<!--.*?-->", re.S),
    re.compile(r"\{\|.*?\|\}", re.S), re.compile(r"<[^>]+>"),
]
WIKI_TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
WIKI_FILE = re.compile(r"\[\[(?:File|Image|चित्र|फाइल|श्रेणी|Category):[^\]]*\]\]", re.I)
WIKI_LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]")
SENTENCE_SPLIT = re.compile(r"[।॥?!\n]+")
WIKI_EMPHASIS = re.compile(r"'{2,}")


def read_wikipedia(path: Path, lang: Lang, verbose: bool):
    """N-gram counts from a Wikipedia pages-articles dump: article text only (namespace 0), markup
    stripped, split into sentences at the danda as well as Latin punctuation. Every
    HELDOUT_MODULO-th sentence is held out, counted in dump order."""
    unigrams, bigrams, trigrams = Counter(), Counter(), Counter()
    heldout: list[list[str]] = []
    sentences = 0
    counter = 0
    in_text = False
    namespace = None
    buffer: list[str] = []

    def flush(text: str):
        nonlocal sentences, counter
        for _ in range(3):  # nested templates, innermost first
            text = WIKI_TEMPLATE.sub(" ", text)
        text = WIKI_FILE.sub(" ", text)
        for pattern in WIKI_DROP:
            text = pattern.sub(" ", text)
        text = WIKI_LINK.sub(r"\1", text)
        text = WIKI_EMPHASIS.sub("", text)
        for sentence in SENTENCE_SPLIT.split(text):
            words = lang.tokens(sentence)
            if len(words) < 2:
                continue
            counter += 1
            if counter % HELDOUT_MODULO == 0:
                heldout.append(words)
                continue
            sentences += 1
            unigrams.update(words)
            bigrams.update(zip(words, words[1:]))
            trigrams.update(zip(words, words[1:], words[2:]))

    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            if "<ns>" in line:
                namespace = line.strip().removeprefix("<ns>").removesuffix("</ns>")
            if not in_text:
                start = line.find("<text")
                if start < 0:
                    continue
                in_text = True
                line = line[line.find(">", start) + 1:]
            end = line.find("</text>")
            if end >= 0:
                buffer.append(line[:end])
                if namespace == "0":
                    flush("".join(buffer))
                buffer.clear()
                in_text = False
            else:
                buffer.append(line)
    log(verbose, f"        {sentences:,} sentences -> {len(unigrams):,} uni / {len(bigrams):,} bi / "
                 f"{len(trigrams):,} tri; {len(heldout):,} held out")
    return unigrams, bigrams, trigrams, heldout


def build(lang: Lang, cache: Path, verbose: bool):
    cache = cache / lang.code
    log(verbose, f"[{lang.code}] reading running text")
    if lang.tatoeba:
        conv_uni, conv_bi, conv_tri, heldout = read_tatoeba(fetch(lang.tatoeba, cache, verbose), lang, verbose)
    else:
        conv_uni, conv_bi, conv_tri, heldout = read_wikipedia(fetch(lang.wikipedia, cache, verbose), lang, verbose)
    log(verbose, "reading dictionary")
    dic_url, aff_url = lang.hunspell
    dic_path = fetch(dic_url, cache / "dic", verbose)
    aff_path = fetch(aff_url, cache / "aff", verbose)
    dictionary = {
        unicodedata.normalize("NFC", w)
        for w in build_lm.read_hunspell(dic_path, aff_path, verbose, accept=lambda w: lang.accept(unicodedata.normalize("NFC", w)))
    }
    attested = {w for w, c in conv_uni.items() if c >= lang.min_evidence}
    # Proofread is not perfect: Tatoeba has "tambien" six times. An attested word that isn't in the
    # dictionary but *is* a dictionary word with its accents removed is an accent-dropping typo by
    # construction — and admitting it would make exactly that typo uncorrectable, since autocorrect
    # never touches a word it believes is real. Legitimate pairs ("esta"/"está") survive, because
    # both halves are in the dictionary.
    stripped_dictionary = {strip_accents(w) for w in dictionary if strip_accents(w) != w}
    accent_typos = {w for w in attested - dictionary if w in stripped_dictionary}
    attested -= accent_typos
    # Same reasoning for a missing space ("porfavor"): the two halves are dictionary words that the
    # same corpus writes apart far more often than together.
    space_typos = set()
    for w in attested - dictionary:
        for i in range(2, len(w) - 1):
            a, b = w[:i], w[i:]
            if a in dictionary and b in dictionary and conv_bi[(a, b)] >= 10 * conv_uni[w]:
                space_typos.add(w)
                break
    attested -= space_typos
    accent_typos |= space_typos
    allowed = dictionary | attested | set(lang.clitics)
    log(verbose, f"        {len(dictionary):,} dictionary + {len(attested):,} attested "
                 f"({len(accent_typos):,} accent-dropped or run-together typos refused) -> {len(allowed):,} admissible")

    freq_p = {}
    if lang.frequency_list:
        freq_p = to_probabilities(read_frequency_list(fetch(lang.frequency_list, cache, verbose), lang, verbose))
    conv_p = to_probabilities(conv_uni)
    w_freq = lang.weight_frequency_unigram if freq_p else 0.0
    w_conv = 1.0 - w_freq
    blended = {w: w_freq * freq_p.get(w, 0.0) + w_conv * conv_p.get(w, 0.0) for w in (set(freq_p) | set(conv_p)) & allowed}
    ranked = sorted(blended.items(), key=lambda kv: -kv[1])[:lang.vocab]
    mass = sum(p for _, p in ranked)
    unigram = {w: p / mass for w, p in ranked}
    vocabulary = sorted(unigram)
    index = {w: i for i, w in enumerate(vocabulary)}
    log(verbose, f"vocabulary: {len(vocabulary):,} words")

    rows: dict = {}
    for (a, b), c in conv_bi.items():
        if c >= build_lm.MIN_CONV_BIGRAM_COUNT and a in index and b in index:
            rows.setdefault(a, Counter())[b] = c
    bigram = {a: to_probabilities(row) for a, row in rows.items()}
    trigram_rows: dict = {}
    for (a, b, c), n in conv_tri.items():
        if n >= build_lm.MIN_CONV_TRIGRAM_COUNT and a in index and b in index and c in index:
            trigram_rows.setdefault((a, b), Counter())[c] = n
    trigram = {k: to_probabilities(r) for k, r in trigram_rows.items()}
    log(verbose, f"bigrams: {sum(map(len, bigram.values())):,}; trigrams: {sum(map(len, trigram.values())):,}")
    model = {"vocabulary": vocabulary, "index": index, "unigram": unigram, "bigram": bigram, "trigram": trigram}
    return model, heldout


# --- Validation ----------------------------------------------------------------------------------


def common_checks(model: dict, contexts: list[str], required: list[str], misspellings: list[str]) -> None:
    """The checks every language shares: the English builder's, with the language's own words."""
    vocabulary, unigram, bigram = model["vocabulary"], model["unigram"], model["bigram"]
    if vocabulary != sorted(vocabulary) or len(set(vocabulary)) != len(vocabulary):
        raise ValidationError("vocabulary is not sorted and unique")
    for context in contexts:
        row = bigram.get(context)
        if not row:
            raise ValidationError(f"no bigram continuations for {context!r}")
        top = [w for w, _ in sorted(row.items(), key=lambda kv: -kv[1])[:10]]
        if len(top) >= 5 and top == sorted(row)[:10]:
            raise ValidationError(f"top continuations of {context!r} are alphabetical")
    for word in required:
        if word not in unigram:
            raise ValidationError(f"{word!r} missing from vocabulary")
    for word in misspellings:
        if word in unigram:
            raise ValidationError(f"{word!r} is a misspelling but is in the vocabulary, so autocorrect "
                                  f"could never fix it — the curation gate is not working")


def expect_top(model: dict, context: str, word: str, within: int) -> None:
    row = model["bigram"].get(context, {})
    top = [w for w, _ in sorted(row.items(), key=lambda kv: -kv[1])[:within]]
    if word not in top:
        raise ValidationError(f"{word!r} not in the top {within} after {context!r}: {top}")


def spanish_probes(model: dict) -> None:
    common_checks(
        model,
        contexts=["de", "que", "la", "el", "no"],
        required=["que", "de", "no", "sí", "qué", "también", "está", "canción", "niño", "año", "gracias",
                  "hola", "por", "favor", "después", "aquí", "así", "adiós"],
        # Accentless forms people type — must be absent, or accent restoration can never fire.
        misspellings=["tambien", "despues", "aqui", "cancion", "adios", "porfavor", "ningun"],
    )
    expect_top(model, "por", "favor", 5)
    expect_top(model, "buenos", "días", 3)
    if model["unigram"]["de"] <= model["unigram"]["canción"]:
        raise ValidationError("unigram probabilities are not frequency-ordered")


def french_probes(model: dict) -> None:
    common_checks(
        model,
        contexts=["je", "de", "la", "le", "c'"],
        required=["je", "tu", "est", "être", "très", "déjà", "français", "ça", "où", "à", "c'", "l'", "j'",
                  "qu'", "d'", "aujourd'hui", "merci", "bonjour"],
        misspellings=["etre", "tres", "deja", "francais", "aujourdhui"],
    )
    expect_top(model, "c'", "est", 3)
    expect_top(model, "je", "suis", 5)


def nepali_probes(model: dict) -> None:
    common_checks(
        model,
        contexts=["नेपाल", "र", "को", "यो"],
        required=["र", "छ", "को", "मा", "हो", "नेपाल", "म", "मेरो", "तपाईं", "गर्न", "थियो", "भएको", "काठमाडौं"],
        misspellings=[],
    )
    # Not "को" — in Nepali that postposition is written joined ("नेपालको"), never as its own word.
    expect_top(model, "नेपाल", "सरकार", 10)
    if model["unigram"]["र"] <= model["unigram"]["काठमाडौं"]:
        raise ValidationError("unigram probabilities are not frequency-ordered")


#: Every Devanagari letter, vowel sign, virama and nasal sign Nepali uses (U+0900–0963, U+0971–097F),
#: plus ZWNJ/ZWJ, which control conjunct rendering inside a word. Not digits, not the danda.
DEVANAGARI = "".join(chr(c) for c in list(range(0x0900, 0x0964)) + list(range(0x0971, 0x0980))) + "‌‍"

LANGS = {
    "es_ES": Lang(
        code="es_ES",
        name="Spanish",
        letters="abcdefghijklmnopqrstuvwxyzáéíóúüñ",
        tatoeba="https://downloads.tatoeba.org/exports/per_language/spa/spa_sentences.tsv.bz2",
        hunspell=(
            "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/es/index.dic",
            "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/es/index.aff",
        ),
        frequency_list="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/es/es_full.txt",
        probes=spanish_probes,
        sources=[
            {"name": "Tatoeba Spanish sentences", "url": "https://tatoeba.org", "license": "CC BY 2.0 FR"},
            {"name": "Hunspell es_ES (LibreOffice, via wooorm/dictionaries)", "url": "https://github.com/wooorm/dictionaries",
             "license": "GPL-3.0-or-later OR LGPL-3.0-or-later OR MPL-1.1"},
            {"name": "FrequencyWords es (OpenSubtitles 2018)", "url": "https://github.com/hermitdave/FrequencyWords",
             "license": "CC BY-SA 3.0"},
        ],
    ),
    "ne_NP": Lang(
        code="ne_NP",
        name="Nepali",
        letters=DEVANAGARI,
        tatoeba=None,
        wikipedia="https://dumps.wikimedia.org/newiki/latest/newiki-latest-pages-articles.xml.bz2",
        hunspell=(
            "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/ne/index.dic",
            "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/ne/index.aff",
        ),
        frequency_list=None,
        # Wikipedia is edited text, and a Devanagari keyboard's worst misspellings (short/long vowel
        # swaps) are corrected by the equivalent-letter channel rather than by keeping them out.
        min_evidence=3,
        probes=nepali_probes,
        sources=[
            {"name": "Nepali Wikipedia", "url": "https://ne.wikipedia.org", "license": "CC BY-SA 4.0"},
            {"name": "Nepali spell-checking dictionary, Madan Puraskar Pustakalaya (via wooorm/dictionaries)",
             "url": "https://github.com/wooorm/dictionaries", "license": "LGPL-2.1"},
        ],
    ),
    "fr_FR": Lang(
        code="fr_FR",
        name="French",
        letters="abcdefghijklmnopqrstuvwxyzàâæçéèêëîïôœùûüÿ",
        apostrophes=True,
        clitics=("jusqu'", "lorsqu'", "puisqu'", "quoiqu'", "qu'", "l'", "d'", "j'", "n'", "s'", "c'", "m'", "t'"),
        tatoeba="https://downloads.tatoeba.org/exports/per_language/fra/fra_sentences.tsv.bz2",
        hunspell=(
            "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/fr/index.dic",
            "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/fr/index.aff",
        ),
        frequency_list="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/fr/fr_full.txt",
        probes=french_probes,
        sources=[
            {"name": "Tatoeba French sentences", "url": "https://tatoeba.org", "license": "CC BY 2.0 FR"},
            {"name": "Grammalecte French dictionary (via wooorm/dictionaries)", "url": "https://grammalecte.net",
             "license": "MPL-2.0"},
            {"name": "FrequencyWords fr (OpenSubtitles 2018)", "url": "https://github.com/hermitdave/FrequencyWords",
             "license": "CC BY-SA 3.0"},
        ],
    ),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("lang", choices=sorted(LANGS))
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=REPO / "build/lm-cache")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    lang = LANGS[args.lang]
    verbose = not args.quiet

    model, heldout = build(lang, args.cache, verbose)
    try:
        lang.probes(model)
    except ValidationError as error:
        print(f"REFUSING TO WRITE: {error}", file=sys.stderr)
        return 1
    log(verbose, "validation passed")

    metadata = {"locale": lang.code, "built": date.today().isoformat(), "sources": lang.sources}
    payload = build_lm.pack(model, metadata)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(payload)

    eval_dir = REPO / "core/src/test/resources/eval" / lang.code
    eval_dir.mkdir(parents=True, exist_ok=True)
    stride = max(1, len(heldout) // HELDOUT_LIMIT)
    (eval_dir / "sentences.txt").write_text("\n".join(" ".join(s) for s in heldout[::stride][:HELDOUT_LIMIT]) + "\n", encoding="utf-8")
    if lang.clitics:
        # How this builder tokenises elided forms, for CliticTokenizerTest to hold the keyboard's
        # runtime split to. One case per line: the raw word, a tab, its tokens space-separated.
        cases = ["l'homme", "L'Homme", "qu'il", "jusqu'à", "lorsqu'on", "c'est", "j'ai", "d'accord",
                 "aujourd'hui", "presqu'île", "l’amour", "s'il", "n'est", "m'appelle", "t'aime", "l'", "maison"]
        (eval_dir / "tokenizer_cases.txt").write_text(
            "\n".join(f"{case}\t{' '.join(lang.tokens(case))}" for case in cases) + "\n", encoding="utf-8")
    print(f"wrote {args.out} ({len(payload) / 1e6:.1f} MB, {len(model['vocabulary']):,} words) and {eval_dir}/sentences.txt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
