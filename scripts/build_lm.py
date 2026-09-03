#!/usr/bin/env python3
"""Builds `app/src/main/assets/lm_en_us.bin`, omakey's bundled language model.

This script exists because its absence caused a shipping bug. The previous assets were generated
by hand, once, and the bigram file came out sorted *alphabetically* with its frequency column
dropped. `DictionarySeeder` then assigned rank by line number, so the keyboard's notion of "most
likely next word" was literally alphabetical order: after "the" it predicted "a, ability, above,
absence". Nothing caught it because there was nothing to catch it with.

So generation is checked in, reproducible, and — most importantly — it refuses to write a model
that fails `validate()`. See that function: it is the actual point of this file.

Usage:
    scripts/build_lm.py [--out PATH] [--cache DIR] [--vocab N] [--verbose]

Sources (downloaded and cached on first run; none are redistributed by this repo):
  * Peter Norvig's count_1w.txt / count_2w.txt — unigram and bigram counts derived from the
    Google Web Trillion Word Corpus. Broad coverage, formal/web register.
  * Tatoeba's English sentence export (CC BY 2.0 FR) — ~2M short, conversational sentences.
    Supplies trigrams, which Norvig's lists don't have at all, and supplies the register a
    keyboard actually needs: people type "are you coming", not "the aforementioned".

The two are interpolated rather than concatenated (see WEIGHT_* below) because neither alone is
right — web counts have the coverage, subtitles-style text has the phrasing.
"""

from __future__ import annotations

import argparse
import bz2
import math
import re
import struct
import sys
import urllib.request
from collections import Counter
from pathlib import Path

# --- Sources -----------------------------------------------------------------------------------

NORVIG_1W = "https://norvig.com/ngrams/count_1w.txt"
NORVIG_2W = "https://norvig.com/ngrams/count_2w.txt"
TATOEBA = "https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2"
HUNSPELL_DIC = "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/en/index.dic"
HUNSPELL_AFF = "https://raw.githubusercontent.com/wooorm/dictionaries/main/dictionaries/en/index.aff"

# A word must be *attested* to enter the vocabulary, not merely frequent. The web corpus is a raw
# crawl: "teh", "recieve", "definately", "seperate" and "helo" are all common enough in it to land
# in the top 150k on frequency alone. Admitting them is catastrophic for a keyboard, because
# correction refuses to touch a word it believes is real — the misspellings become uncorrectable,
# which is precisely the failure the first build of this model shipped with.
#
# Google's FST decoder paper makes the same point: keyboard language models are "trained to a fixed
# vocabulary that has been hand-curated to eliminate misspellings, erroneous capitalizations, and
# other undesired artifacts."
#
# Two gates, unioned:
#   1. The Hunspell en_US spell-checking dictionary, affix rules expanded. This is the same word
#      set a spell checker accepts, and it is genuinely curated — unlike the large scraped word
#      lists in circulation, which readily contain "untill" and "wierd" and would hand us the same
#      uncorrectable-typo problem in a different disguise.
#   2. Repeated use in the conversational corpus, which is human-written and proofread rather than
#      crawled. Admits brands, slang and contractions the dictionary has no entry for; excludes
#      noise, which occurs at most once there ("seperate" appears exactly once in 2M sentences,
#      while "google" appears 223 times and "facebook" 740).
MIN_CONVERSATIONAL_EVIDENCE = 5

# Interpolation weights. Web data carries unigram coverage; conversational data carries phrasing,
# so it gets the larger share of the contextual tiers where register matters most.
WEIGHT_WEB_UNIGRAM = 0.6
WEIGHT_CONV_UNIGRAM = 0.4
WEIGHT_WEB_BIGRAM = 0.45
WEIGHT_CONV_BIGRAM = 0.55

# Words are lowercase, may contain an internal apostrophe ("don't", "o'clock"), and must start and
# end with a letter. The old wordlist was alphabetic-only, which is precisely why contractions had
# to be special-cased in a hand-curated map in AutocorrectIndex — the dictionary literally could
# not represent "don't".
WORD_RE = re.compile(r"^[a-z]+(?:'[a-z]+)*$")
MAX_WORD_LEN = 24

MAGIC = b"OMLM"
FORMAT_VERSION = 1
HEADER_BYTES = 64
TOP_UNIGRAMS = 512

# Pruning thresholds keep the asset inside its size budget and drop the long tail of n-grams seen
# once, which is noise rather than signal at this corpus size.
MIN_CONV_BIGRAM_COUNT = 2
MIN_CONV_TRIGRAM_COUNT = 3

# Only the head of each continuation row is kept. Prediction never shows more than a handful, and
# for *scoring* a candidate that falls outside the head, backing off to the shorter context is a
# better estimate than the noisy tail probability would have been anyway. Trigram rows are capped
# harder because there are an order of magnitude more of them and each is estimated from far less
# evidence.
MAX_BIGRAM_ROW = 64
MAX_TRIGRAM_ROW = 12

# Log-probabilities are stored as int16 scaled by this factor rather than float32. Values live in
# roughly [-20, 0] nats, so a scale of 1000 keeps three decimal places of precision inside int16's
# range — far finer than any ranking decision needs, at half the bytes.
LOGP_SCALE = 1000
LOGP_MIN = -32.0


def log(verbose: bool, message: str) -> None:
    if verbose:
        print(message, file=sys.stderr, flush=True)


def fetch(url: str, cache: Path, verbose: bool) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / url.rsplit("/", 1)[-1]
    if target.exists() and target.stat().st_size > 0:
        log(verbose, f"cached  {target.name}")
        return target
    log(verbose, f"fetch   {url}")
    with urllib.request.urlopen(url, timeout=900) as response, open(target, "wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)
    return target


def normalize(token: str) -> str | None:
    token = token.strip().lower().strip(".,!?;:\"()[]")
    # Unicode right single quote is extremely common in real text and must fold onto the ASCII
    # apostrophe, or "don't" and "don’t" become two different words.
    token = token.replace("’", "'")
    if not token or len(token) > MAX_WORD_LEN or not WORD_RE.match(token):
        return None
    return token


# --- Corpus reading ----------------------------------------------------------------------------


def read_norvig_unigrams(path: Path, verbose: bool) -> Counter:
    counts = Counter()
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.split("\t")
            if len(parts) != 2:
                continue
            word = normalize(parts[0])
            if word:
                counts[word] += int(parts[1])
    log(verbose, f"        {len(counts):,} web unigrams")
    return counts


def read_norvig_bigrams(path: Path, verbose: bool) -> Counter:
    counts = Counter()
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 2:
                continue
            pair = parts[0].split()
            if len(pair) != 2:
                continue
            first, second = normalize(pair[0]), normalize(pair[1])
            if first and second:
                counts[(first, second)] += int(parts[1])
    log(verbose, f"        {len(counts):,} web bigrams")
    return counts


def read_hunspell(dic_path: Path, aff_path: Path, verbose: bool) -> set[str]:
    """The Hunspell dictionary with its affix rules applied, giving inflected forms too.

    The `.dic` file lists only stems, each tagged with the affix flags it accepts — "cat/SM" rather
    than "cat", "cats", "cat's". Taking stems alone would leave every plural and past tense outside
    the vocabulary, so they get expanded here.

    Only a single affix application is performed (no prefix+suffix cross-products, no continuation
    flags). That under-generates slightly, which is the safe direction: a missing inflection falls
    back to gate 2 or is simply absent, whereas over-generating would start inventing words.
    """
    rules: dict[str, list[tuple[str, str, str, str]]] = {}
    lines = aff_path.read_text(encoding="utf-8", errors="replace").splitlines()
    index = 0
    while index < len(lines):
        parts = lines[index].split()
        index += 1
        if len(parts) < 4 or parts[0] not in ("SFX", "PFX"):
            continue
        kind, flag = parts[0], parts[1]
        try:
            count = int(parts[3])
        except ValueError:
            continue
        for _ in range(count):
            if index >= len(lines):
                break
            rule = lines[index].split()
            index += 1
            if len(rule) < 4:
                continue
            strip, add = rule[2], rule[3].split("/")[0]
            condition = rule[4] if len(rule) > 4 else "."
            rules.setdefault(flag, []).append((kind, strip, add, condition))

    words: set[str] = set()
    for line_number, line in enumerate(dic_path.read_text(encoding="utf-8", errors="replace").splitlines()):
        if line_number == 0:  # the count header
            continue
        stem, _, flags = line.strip().partition("/")
        stem = stem.strip().lower().replace("’", "'")
        if not stem:
            continue
        if WORD_RE.match(stem):
            words.add(stem)
        for flag in flags:
            for kind, strip, add, condition in rules.get(flag, ()):
                try:
                    if kind == "SFX":
                        if not re.search(condition + "$", stem):
                            continue
                        base = stem[: -len(strip)] if strip != "0" and stem.endswith(strip) else stem
                        candidate = base + (add if add != "0" else "")
                    else:
                        if not re.match(condition, stem):
                            continue
                        base = stem[len(strip):] if strip != "0" and stem.startswith(strip) else stem
                        candidate = (add if add != "0" else "") + base
                except re.error:
                    continue
                if WORD_RE.match(candidate) and len(candidate) <= MAX_WORD_LEN:
                    words.add(candidate)
    log(verbose, f"        {len(words):,} dictionary words (stems + affixes)")
    return words


def read_conversational(path: Path, verbose: bool) -> tuple[Counter, Counter, Counter]:
    """Unigram, bigram and trigram counts from the Tatoeba sentence export.

    Sentence boundaries are respected — n-grams never span them, since "…tomorrow. Where…" tells
    us nothing about what follows "tomorrow" in the middle of a sentence.
    """
    unigrams: Counter = Counter()
    bigrams: Counter = Counter()
    trigrams: Counter = Counter()
    sentences = 0
    with bz2.open(path, "rt", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            words = [w for w in (normalize(t) for t in parts[2].split()) if w]
            if not words:
                continue
            sentences += 1
            unigrams.update(words)
            bigrams.update(zip(words, words[1:]))
            trigrams.update(zip(words, words[1:], words[2:]))
    log(verbose, f"        {sentences:,} sentences -> {len(unigrams):,} uni / "
                 f"{len(bigrams):,} bi / {len(trigrams):,} tri")
    return unigrams, bigrams, trigrams


# --- Model assembly ----------------------------------------------------------------------------


def to_probabilities(counts: Counter) -> dict:
    total = sum(counts.values())
    return {key: value / total for key, value in counts.items()} if total else {}


def build(vocab_size: int, cache: Path, verbose: bool) -> dict:
    log(verbose, "reading web unigrams")
    web_unigrams = read_norvig_unigrams(fetch(NORVIG_1W, cache, verbose), verbose)
    log(verbose, "reading web bigrams")
    web_bigrams = read_norvig_bigrams(fetch(NORVIG_2W, cache, verbose), verbose)
    log(verbose, "reading conversational corpus")
    conv_unigrams, conv_bigrams, conv_trigrams = read_conversational(
        fetch(TATOEBA, cache, verbose), verbose
    )

    # Curated word set — see MIN_CONVERSATIONAL_EVIDENCE for why frequency alone is not enough.
    log(verbose, "reading dictionary")
    dictionary = read_hunspell(
        fetch(HUNSPELL_DIC, cache, verbose), fetch(HUNSPELL_AFF, cache, verbose), verbose
    )
    attested = {word for word, count in conv_unigrams.items() if count >= MIN_CONVERSATIONAL_EVIDENCE}
    allowed = dictionary | attested
    log(verbose, f"        {len(dictionary):,} dictionary + {len(attested):,} attested "
                 f"-> {len(allowed):,} admissible")

    # Vocabulary: blend both sources' unigram *probabilities* (not raw counts, whose scales differ
    # by orders of magnitude), then keep the most probable `vocab_size` admissible words.
    web_p = to_probabilities(web_unigrams)
    conv_p = to_probabilities(conv_unigrams)
    blended = {}
    for word in (set(web_p) | set(conv_p)) & allowed:
        blended[word] = (
            WEIGHT_WEB_UNIGRAM * web_p.get(word, 0.0)
            + WEIGHT_CONV_UNIGRAM * conv_p.get(word, 0.0)
        )
    ranked = sorted(blended.items(), key=lambda kv: -kv[1])[:vocab_size]
    mass = sum(p for _, p in ranked)
    unigram = {word: p / mass for word, p in ranked}
    vocabulary = sorted(unigram)  # lexicographic: makes prefix lookup a binary-search range
    index = {word: i for i, word in enumerate(vocabulary)}
    log(verbose, f"vocabulary: {len(vocabulary):,} words")

    # Bigrams as conditional probabilities P(second | first), interpolated across sources.
    web_bi_by_first: dict = {}
    for (first, second), count in web_bigrams.items():
        if first in index and second in index:
            web_bi_by_first.setdefault(first, Counter())[second] = count
    conv_bi_by_first: dict = {}
    for (first, second), count in conv_bigrams.items():
        if count < MIN_CONV_BIGRAM_COUNT or first not in index or second not in index:
            continue
        conv_bi_by_first.setdefault(first, Counter())[second] = count

    bigram: dict = {}
    for first in set(web_bi_by_first) | set(conv_bi_by_first):
        web_row = to_probabilities(web_bi_by_first.get(first, Counter()))
        conv_row = to_probabilities(conv_bi_by_first.get(first, Counter()))
        row = {}
        for second in set(web_row) | set(conv_row):
            row[second] = (
                WEIGHT_WEB_BIGRAM * web_row.get(second, 0.0)
                + WEIGHT_CONV_BIGRAM * conv_row.get(second, 0.0)
            )
        total = sum(row.values())
        if total > 0:
            bigram[first] = {k: v / total for k, v in row.items()}
    log(verbose, f"bigrams: {sum(len(r) for r in bigram.values()):,} in {len(bigram):,} contexts")

    # Trigrams come from the conversational corpus alone — the web lists have no 3-grams.
    trigram_rows: dict = {}
    for (a, b, c), count in conv_trigrams.items():
        if count < MIN_CONV_TRIGRAM_COUNT:
            continue
        if a in index and b in index and c in index:
            trigram_rows.setdefault((a, b), Counter())[c] = count
    trigram = {context: to_probabilities(row) for context, row in trigram_rows.items()}
    log(verbose, f"trigrams: {sum(len(r) for r in trigram.values()):,} in {len(trigram):,} contexts")

    return {
        "vocabulary": vocabulary,
        "index": index,
        "unigram": unigram,
        "bigram": bigram,
        "trigram": trigram,
    }


# --- Validation --------------------------------------------------------------------------------


class ValidationError(Exception):
    pass


def validate(model: dict) -> None:
    """Refuses to emit a model that repeats the bug this script was written to prevent.

    Every check below is a property the *previous* asset silently violated. They are cheap; the
    failure they guard against shipped to users and survived multiple releases.
    """
    vocabulary = model["vocabulary"]
    unigram = model["unigram"]
    bigram = model["bigram"]

    if vocabulary != sorted(vocabulary):
        raise ValidationError("vocabulary is not lexicographically sorted — prefix search needs it")
    if len(set(vocabulary)) != len(vocabulary):
        raise ValidationError("vocabulary contains duplicates")

    # The headline check. If bigram rows were ordered alphabetically rather than by probability —
    # the exact defect in the old asset — this fails.
    for context in ("the", "a", "i", "to"):
        row = bigram.get(context)
        if not row:
            raise ValidationError(f"no bigram continuations for {context!r}")
        top = sorted(row.items(), key=lambda kv: -kv[1])[:10]
        alphabetical = sorted(row)[:10]
        if [w for w, _ in top] == alphabetical:
            raise ValidationError(
                f"top continuations of {context!r} are in alphabetical order — "
                f"frequency data was lost somewhere in generation"
            )

    # Words a keyboard cannot be without. "i" having zero continuations was a real symptom.
    for word in ("i", "the", "you", "we", "a", "to", "is", "how", "thanks"):
        if word not in unigram:
            raise ValidationError(f"{word!r} missing from vocabulary")
        if word not in bigram:
            raise ValidationError(f"{word!r} has no bigram continuations")

    # Known orderings any usable English model must reproduce.
    for context, expected, worse in [
        ("i", "am", "amp"),
        ("thank", "you", "your"),
        ("how", "are", "arm"),
    ]:
        row = bigram.get(context, {})
        if row.get(expected, 0.0) <= row.get(worse, 0.0):
            raise ValidationError(
                f"P({expected}|{context}) should exceed P({worse}|{context}) — "
                f"got {row.get(expected, 0.0):.3e} vs {row.get(worse, 0.0):.3e}"
            )

    # The post-correction example from Google's FST decoder paper: "a while lot" -> "a whole lot".
    # Deliberately checked at the trigram tier, not the bigram one. At the bigram tier "a while"
    # legitimately *beats* "a whole" ("wait a while", "in a while") — the evidence that distinguishes
    # them is the word that comes after, which is the entire reason post-correction needs right
    # context and the bigram model alone cannot do this job.
    trigram = model["trigram"]
    whole_lot = trigram.get(("a", "whole"), {}).get("lot", 0.0)
    while_lot = trigram.get(("a", "while"), {}).get("lot", 0.0)
    if whole_lot <= while_lot:
        raise ValidationError(
            f"P(lot|a,whole) should exceed P(lot|a,while) — got {whole_lot:.3e} vs {while_lot:.3e}; "
            f"the trigram tier cannot support post-correction"
        )

    # Unigram order must track real English frequency, not insertion or alphabetical order.
    if unigram["the"] <= unigram["keyboard"]:
        raise ValidationError("unigram probabilities are not frequency-ordered")

    # Contractions must be first-class vocabulary entries now that words can hold apostrophes.
    for word in ("don't", "it's", "i'm", "you're"):
        if word not in unigram:
            raise ValidationError(f"contraction {word!r} missing from vocabulary")

    # Misspellings must NOT be in the vocabulary. A word the model believes is real is a word
    # autocorrect will never fix, so every entry here would be an uncorrectable typo — and all of
    # these are frequent enough in raw web text to qualify on frequency alone.
    for word in ("teh", "recieve", "definately", "seperate", "acheive", "occured", "untill",
                 "helo", "thisis", "alot", "becuase"):
        if word in unigram:
            raise ValidationError(
                f"{word!r} is in the vocabulary — it is a misspelling, so autocorrect would refuse "
                f"to fix it. The curation gate is not working."
            )

    # ...while words that are not in a dictionary but which people genuinely type must survive it.
    for word in ("google", "facebook", "email", "ok"):
        if word not in unigram:
            raise ValidationError(f"{word!r} was filtered out — the curation gate is too strict")


# --- Binary encoding ---------------------------------------------------------------------------


def pack(model: dict) -> bytes:
    """Little-endian, 4-byte aligned, laid out for `mmap` rather than parsing.

    Nothing here is decoded at load time on device: `LanguageModel` maps the file and reads through
    it directly, so startup cost is a page fault rather than building 150k heap objects in an
    input-method process the OS kills freely.
    """
    vocabulary = model["vocabulary"]
    index = model["index"]
    unigram = model["unigram"]
    bigram = model["bigram"]
    trigram = model["trigram"]

    blob = bytearray()
    offsets = [0]
    for word in vocabulary:
        blob.extend(word.encode("utf-8"))
        offsets.append(len(blob))
    while len(blob) % 4:
        blob.append(0)

    def quantize(probability: float) -> int:
        return int(round(max(math.log(probability), LOGP_MIN) * LOGP_SCALE))

    unigram_logp = [quantize(unigram[word]) for word in vocabulary]
    top_unigrams = [
        index[word]
        for word in sorted(vocabulary, key=lambda w: -unigram[w])[:TOP_UNIGRAMS]
    ]

    # Bigrams in CSR form keyed by previous-word id; each row sorted by descending probability so
    # "best N continuations" is a prefix of the row rather than a sort at query time.
    bigram_start = [0] * (len(vocabulary) + 1)
    bigram_word: list[int] = []
    bigram_logp: list[int] = []
    for word_id, word in enumerate(vocabulary):
        bigram_start[word_id] = len(bigram_word)
        row = bigram.get(word)
        if row:
            for second, probability in sorted(row.items(), key=lambda kv: -kv[1])[:MAX_BIGRAM_ROW]:
                bigram_word.append(index[second])
                bigram_logp.append(quantize(probability))
    bigram_start[len(vocabulary)] = len(bigram_word)

    # Trigram contexts sorted by (first, second) id so lookup is a binary search.
    contexts = sorted(trigram, key=lambda ctx: (index[ctx[0]], index[ctx[1]]))
    trigram_a = [index[a] for a, _ in contexts]
    trigram_b = [index[b] for _, b in contexts]
    trigram_start = [0] * (len(contexts) + 1)
    trigram_word: list[int] = []
    trigram_logp: list[int] = []
    for position, context in enumerate(contexts):
        trigram_start[position] = len(trigram_word)
        row = sorted(trigram[context].items(), key=lambda kv: -kv[1])[:MAX_TRIGRAM_ROW]
        for third, probability in row:
            trigram_word.append(index[third])
            trigram_logp.append(quantize(probability))
    trigram_start[len(contexts)] = len(trigram_word)

    header = bytearray(HEADER_BYTES)
    struct.pack_into(
        "<4sIIIIIII", header, 0,
        MAGIC, FORMAT_VERSION, len(vocabulary), len(blob),
        len(bigram_word), len(contexts), len(trigram_word), len(top_unigrams),
    )

    def u32(values): return struct.pack(f"<{len(values)}I", *values)

    def i16(values):
        # Padded to a 4-byte boundary so every section that follows stays aligned for the reader's
        # IntBuffer/ShortBuffer views.
        packed = struct.pack(f"<{len(values)}h", *values)
        return packed + b"\0" * (-len(packed) % 4)

    return b"".join([
        bytes(header), bytes(blob),
        u32(offsets), i16(unigram_logp), u32(top_unigrams),
        u32(bigram_start), u32(bigram_word), i16(bigram_logp),
        u32(trigram_a), u32(trigram_b), u32(trigram_start),
        u32(trigram_word), i16(trigram_logp),
    ])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=Path("core/src/main/assets/lm_en_us.bin"))
    parser.add_argument("--cache", type=Path, default=Path("build/lm-cache"))
    parser.add_argument("--vocab", type=int, default=150_000)
    parser.add_argument("--verbose", action="store_true", default=True)
    args = parser.parse_args()

    model = build(args.vocab, args.cache, args.verbose)

    try:
        validate(model)
    except ValidationError as error:
        print(f"REFUSING TO WRITE: {error}", file=sys.stderr)
        return 1
    log(args.verbose, "validation passed")

    payload = pack(model)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(payload)
    print(f"wrote {args.out} ({len(payload) / 1e6:.1f} MB, {len(model['vocabulary']):,} words)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
