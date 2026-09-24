#!/usr/bin/env python3
"""Writes core/src/test/resources/lm/tiny_multiscript.bin, the non-English model LanguageModelV2Test
and the equivalent-letter tests run against.

Built with build_lm.write_binary — the same serialiser real models use — so the tests exercise the
exact bytes a language pack would contain, not a hand-rolled imitation. Regenerate after any format
change: `python3 scripts/build_lm_test_fixture.py`.
"""
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import build_lm  # noqa: E402

# Word -> relative frequency. Spanish with accents and ñ, plus Devanagari with vowel signs,
# a halant conjunct and anusvara — the characters the old ASCII-only format could not hold.
COUNTS = {
    "el": 900, "la": 800, "mi": 500, "casa": 300, "como": 250, "cómo": 200, "canción": 120,
    "cancha": 60, "niño": 90, "año": 110,
    "मेरो": 400, "नमस्ते": 150, "किताब": 100, "संसार": 50,
}

vocabulary = sorted(COUNTS)
index = {w: i for i, w in enumerate(vocabulary)}
total = sum(COUNTS.values())


def q(p: float) -> int:
    return int(round(max(math.log(p), build_lm.LOGP_MIN) * build_lm.LOGP_SCALE))


bigrams = {"mi": {"canción": 0.6, "casa": 0.4}, "मेरो": {"किताब": 1.0}}
bigram_start, bigram_word, bigram_logp = [0] * (len(vocabulary) + 1), [], []
for i, w in enumerate(vocabulary):
    bigram_start[i] = len(bigram_word)
    for second, p in sorted(bigrams.get(w, {}).items(), key=lambda kv: -kv[1]):
        bigram_word.append(index[second])
        bigram_logp.append(q(p))
bigram_start[len(vocabulary)] = len(bigram_word)

sections = build_lm.Sections(
    vocabulary=vocabulary,
    unigram_logp=[q(COUNTS[w] / total) for w in vocabulary],
    top_unigrams=[index[w] for w in sorted(vocabulary, key=lambda w: -COUNTS[w])],
    bigram_start=bigram_start, bigram_word=bigram_word, bigram_logp=bigram_logp,
    trigram_a=[], trigram_b=[], trigram_start=[0], trigram_word=[], trigram_logp=[],
)
out = Path(__file__).resolve().parent.parent / "core/src/test/resources/lm/tiny_multiscript.bin"
out.write_bytes(build_lm.write_binary(sections, {"locale": "test", "sources": []}))
print(f"wrote {out}")
