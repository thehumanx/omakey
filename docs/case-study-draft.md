# omakey — Fleksy-inspired keyboard for Android

Product, Solo build, AI-assisted / 14 min read

**I used one keyboard app for ten years. Then it died, and nothing else on the Play Store felt like it.** So I started building my own with Claude Code, three weeks ago, with no spec and no wireframes — just a paragraph of "here's why." Eleven releases and a full autocorrect rebuild later, this is the story of what got built, what got argued about, and the two places this session where Claude built something from its own plan, measured it, and then quietly took it back out because the numbers said no.

### Role
Founder, sole designer & product owner

### Timeline
~3 weeks (first commit Aug 11, 2026 → v3.0.0 Sep 3, 2026), 25 commits, 11 tagged releases

### Version
v3.0.0

### Stack
Kotlin, Jetpack Compose, Room, WorkManager — Android, multi-module (`app` / `core` / `extensions-api` / `extensions-builtin`)

### Tools
Claude Code

### Status
Public, GPLv3-licensed, not on the Play Store yet — sideload only

### Website
[github.com/thehumanx/omakey](https://github.com/thehumanx/omakey)

**Archive last updated**
Sep 03, 2026

---

## TL;DR

- **I lost my keyboard of ten years and couldn't find a replacement, so I described what I missed about it and let Claude Code build it from scratch** — no Figma, no spec, just a paragraph explaining why Fleksy mattered to me.
- **The founding technical bet — gesture shortcuts instead of glide-typing — was Claude's idea, not mine.** It went and researched what Fleksy actually was before agreeing to "replicate" it, and talked me out of the assumption baked into my own request.
- **Three weeks in, I asked for "research thoroughly, make my keyboard's suggestions outperform Gboard."** What came back was a full autocorrect rebuild grounded in Google's own published keyboard research — and, midway through, the discovery that my keyboard had been suggesting words in *alphabetical order* since day one and nobody had noticed, including me.
- **Twice, Claude built something the approved plan called for, measured it, and reversed itself in writing.** Not because I pushed back — because it tested its own idea and the numbers disagreed with it. That's the part of this case study I most wanted on record.

---

## The problem

I used Fleksy for ten years. Not casually — it was the only keyboard I've typed on since I switched to Android, gesture shortcuts and all: swipe to delete a word, swipe to cycle punctuation, swipe up to save a word to your dictionary instead of hunting through a settings menu. Then Fleksy officially stopped development and came off the Play Store, and I went looking for a replacement.

There wasn't one. Everything else either wanted to glide-type — a feature I've never used and don't want — or came bundled with the kind of tracking and cloud-suggestion machinery I don't trust a thing that sees every word I type to have. So I opened a session and gave Claude Code the whole pitch in one paragraph:

> "i want you to build an android keyboard that is the exact replicate of fleksy. why i am building this: 1. fleksy officially stopped building the keyboard and is no longer available in the playstore 2. i have been using fleksy since 10 years and there are no other keyboard out there that is minimal, fast and un—"

That's it. No wireframes, no feature list, no architecture doc going in. I know how to review code and reason about a system — I don't write Kotlin day to day, and I wasn't going to pretend I could hand-spec an IME. The bet was the same one behind everything else I've built this way: if I bring the judgment and the taste, how much of the execution can I actually hand off.

## Process — from a paragraph to a signed APK

There was no Figma step this time, unlike other projects of mine. omakey doesn't have a distinct visual identity to design around — it's a system keyboard, and the thing that matters is how it *behaves*, not how it looks. So the process looked different:

1. **State the intent, not the spec.** "Replicate Fleksy" was the brief. Claude was expected to fill in what that actually meant.
2. **Push back on the brief before building anything.** More on this below — the first real decision of the project came from Claude disagreeing with my own framing.
3. **Write the reasoning down as a living internal document**, `AGENTS.md` — every real bug, every reversed decision, every "user reported X, root cause was Y" gets a dated entry. It's gitignored, technical, and never meant for the README. I didn't ask for this convention explicitly; it started early and just kept being followed.
4. **Ship in small, tagged increments.** Eleven releases in three weeks, each with a real changelog entry, not a version bump for its own sake.
5. **When a request is genuinely ambitious, stop and research before touching code.** This is what happened with the request that this case study is mostly about — described in full below.

## Key decisions (and the trade-offs behind them)

### Decision 1 — Gesture shortcuts over glide-typing, and it wasn't my idea

**The trigger:** I asked for an "exact replicate" of Fleksy. Every mainstream keyboard — Gboard, SwiftKey, Fleksy's own later versions — leads with glide/swipe-to-type as the headline feature.

**What actually happened:** before writing anything, Claude went and researched what Fleksy actually was across its lifetime, rather than taking "replicate" at face value, and came back with a correction to my own brief:

> "That was more a beta/late feature. So authentic Fleksy-replica priority should be gesture shortcuts + extensions + speed, not necessarily best-in-class glide typing."

**The trade-off I accepted on purpose:** omakey doesn't do glide-typing, full stop. It's documented in the project's internal notes as a deliberate decision, not a missing feature. Every gesture is a shortcut for an action instead — swipe left to delete a word, swipe up/down to cycle suggestions or punctuation. It's a real product bet against the grain of the entire category, and it's the one founding decision in this project that wasn't mine.

### Decision 2 — Offline-by-default, and the honesty it forced later

**The trigger:** omakey requests zero network permissions. No `INTERNET` in the manifest. That was the privacy pitch from day one — not "we don't send your keystrokes anywhere," but "we *can't*, and you can verify that yourself in thirty seconds."

**Where it got tested:** a few days in, I asked for "perfect" next-word prediction and told Claude to use the internet if it needed to. That's a direct collision with the offline promise. Claude didn't quietly pick a side — it laid out the real options (a better offline model, genuine on-device ML, or actually breaking the promise for cloud calls), and then said something I didn't expect:

> "I want to be straight with you rather than fake it — a real trained neural next-word model (data collection, training, quantization to TFLite, on-device inference) is realistically multi-day work requiring an ML training pipeline not available in this environment."

It could have shipped something and called it "AI-powered." Instead it told me the honest version was a better offline word-frequency model, asked me to pick the long-term direction anyway, and left a note in its own internal documentation that this was a stopgap, not the real answer. That note sat there, unaddressed, for three weeks — until this session.

**The trade-off that's still live:** offline-by-default means every improvement to how smart the keyboard feels has to come from data and math that fits on the phone, not a server call. That constraint is the entire reason this session's rebuild was hard.

### Decision 3 — the autocorrect rebuild, and the bug nobody had noticed

**The trigger:** three weeks and ten releases in, I opened a session with one paragraph:

> "lets look into the autocorrect suggestions and prediction suggestion engine. the keyboard suggestions are not perfect... can we research throughly and comeup with a perfect autocorrection/prediction suggestion enginer for a perfect keyboard based on existing patterns, data and work on how my keyboard can outperform all."

**What Claude actually did before writing code:** read Google's own published research on how Gboard's decoder works, a Google paper on personalizing the keyboard's spatial model to an individual's fingers, and an academic study (VelociTap) that measured, with real typists, exactly how much worse a keyboard gets when it ignores where a finger actually landed on a key — 20% error rate versus under 5%. Then, instead of assuming omakey's engine had the same problems, it queried the actual data omakey was shipping.

**What it found:** type "the" into omakey, and instead of a real next-word guess, it suggested — in order — **"a, ability, above, absence, absolute."** Alphabetical. The word "i," one of the most common words in English, had zero suggestions attached to it at all. At some point, early in the project, the file that taught the keyboard which words statistically follow which other words had lost its "how common is this" information, and the code had silently started reading alphabetical order as popularity. It had been quietly making the keyboard worse at its one job since before I ever installed it on my own phone.

**The trade-off I chose, when asked:** Claude didn't just start rebuilding. It asked me three real questions first — patch the data or rebuild the whole scoring engine, how much bigger the app was allowed to get for better predictions, and whether the keyboard should start learning from what I actually type by default. I picked the ambitious answer each time. It wrote an implementation plan and asked for approval before touching a file.

### Decision 4 — personalization, and fixing a bug I'd shipped once and then killed

**The trigger:** swiping up to save a word to omakey's dictionary — a name, slang, a project name — barely changed anything in practice. The word you saved got filed into the exact same bucket as the built-in 60,000-word dictionary, so it was mathematically close to impossible for a word you'd typed once to ever outrank a word you'd never typed at all.

**The harder context underneath it:** an earlier version of omakey *did* learn from everything you typed automatically, and it got ripped out entirely, because it silently marked every uncaught typo as a real word forever — confirmed on my own phone, mid-typing.

**The fix:** personal words now live completely separate from the built-in dictionary and only get blended in at the moment of ranking — never merged into the same bucket. And the thing that was actually conflated the first time got split into two: *the keyboard has seen this word* is not the same claim as *the keyboard trusts this word enough to stop flagging it as a typo*. A word needs to show up a few times before autocorrect stops touching it; a word you deliberately saved is trusted immediately, because I said so.

## What I considered and didn't build — and why

This is the part of the session I actually most wanted written down, because it isn't me pushing back on Claude — it's Claude pushing back on its own approved plan, with numbers.

- **A faster search structure (a trie), to catch more typos by widening the candidate pool.** The plan called the existing "only check words starting with the same letter as the typo" shortcut a correctness ceiling. Claude removed it to test that claim. Accuracy got *worse*, and the keyboard got fourteen times slower. It put the verdict in writing: "the original design comment defending this prune was right; the plan's criticism of it was not." The trie was never built.
- **Widening the auto-correct search to catch typos further from the intended word.** Also in the plan. Also tested, once the first idea was ruled out and a proper diagnostic pointed at the real bottleneck instead. The result: it genuinely helped, but *only* for the suggestion strip you swipe through — for the silent auto-correct that fixes typos without asking, the same change produced more wrong corrections than right ones. So it shipped two different limits: cautious where a mistake is invisible and irreversible, more generous where you can see it and just swipe past it.
- **Threading real finger-touch coordinates into every correction**, the single biggest lever the published research points to. Claude built it — a full system for tracking exactly where your finger landed on each key, not just which key it resolved to — and then built a second thing just to test whether it was worth it: a simulated typist that drifts slightly off-key on real words, so the same typo could be corrected twice, once with the real coordinates and once without. The gain was real but tiny — about half a percentage point — because when a finger actually slips, it lands on the *key right next to* the one you meant, and the keyboard already knew those two keys were neighbors without needing your exact touch point. The code is fully built and tested. It's switched off. I didn't ask for that restraint; a number said it wasn't worth the risk yet.
- **A real on-device neural prediction model** — the thing from Decision 2, three weeks unaddressed. Still not built. Still honestly logged as needing training infrastructure that doesn't exist in this environment, not quietly reinterpreted into something smaller and called done.

## Challenges

**Offline-by-default and "make it smarter" pull against each other, permanently, not just once.** Every gain in prediction quality this session came from putting more data and more computation on the phone itself, and that has a real cost in app size and battery that a cloud call wouldn't have. This tension showed up as far back as the second week, when I reported inaccurate typing and battery drain in the same message and Claude's investigation ran straight into the same wall from the other direction.

**This has never been tested on a real phone with the new engine, and I know it.** Everything in this session is verified by an automated testing harness and a clean build — genuinely solid ground, not a guess — but two database migrations run silently the first time an existing install opens after this update, and "your saved words survive the upgrade" is still a claim resting on careful reasoning, not a phone in my hand yet.

**The old "learn from everything" bug is the kind of thing that could come back in a different shape.** Personalization is deliberately re-enabled now, with the knowing-versus-trusting split as the fix — but it's a system I have to keep watching, not a problem I get to consider closed just because the specific old bug is gone.

## Outcomes

|  | before this session | after |
|---|---|---|
| Typos autocorrect fixes correctly | 18% | **33%** |
| Right word actually shown in the suggestion strip | 39% | **55%** |
| Correct next-word guess | 8% | **25%** |
| Time to check one word | 0.17ms | 0.28ms — still ~70× inside the ~20ms budget a keypress has before it feels laggy |

Once the numbers held and every test passed, this became a real release: version bumped to 3.0.0, changelog and README rewritten in plain language, the internal engineering notes updated with the full story — including both reversed decisions, not just the ones that worked — a signed release APK built and verified against the same certificate as every prior release, and pushed as the new latest release on GitHub.

## How I actually use AI in my day-to-day process

I don't write the Kotlin. What I do is set the constraints and then hold the line on them across sessions — three weeks apart, the "offline-by-default" decision from week one is still the thing everything gets checked against, and I don't have to re-state it every time because it's written down where the agent can find it.

The actual back-and-forth in this project rarely looks like me correcting code. It looks more like this, from partway through the autocorrect session, when I was asked to choose between three options instead of being handed a decision:

> **Claude:** "I found the core problem: the bundled bigram corpus lost its frequency counts... How much should this plan cover?"
>
> **Me:** Full engine, staged plan.
>
> **Claude:** "A real language model with counts is bigger than the current 2MB of assets... What APK budget can I spend?"
>
> **Me:** ~12MB, maximum quality.
>
> **Claude:** "...even then a learned word can't outrank the seeded vocabulary... What should the default be?"
>
> **Me:** Implicit learning on, with incognito.

Three decisions, three sentences from me, because the trade-offs had already been laid out clearly enough that I didn't need more. The disagreements that mattered more, in this project, weren't between the two of us — they were Claude catching its own earlier reasoning being wrong, mid-session, and saying so in the same document it had written the original plan into.

## What I learned building omakey

**Letting an agent research before it builds changes what gets built.** The gesture-over-glide-typing decision, the alphabetical-suggestion bug, both self-reversals in this session — none of them happen if the instruction is just "make autocorrect better" and the response is code. They happen because the instruction was "research thoroughly" and it was actually taken as an instruction, not a rhetorical flourish.

**A living internal decision log is worth more than it looks like early on.** `AGENTS.md` started as an afterthought convention and became the thing that let a session three weeks later cite the exact reasoning behind a decision instead of re-deriving it, and — more importantly — let this session's two reversed hypotheses get written down as lessons instead of quietly disappearing into a diff.

**"It got worse when I tested my own idea" is a sentence I want to see more of, not less.** It's not a failure mode. It's what separates a plan that survives contact with real measurement from one that just sounds right.

**I still don't have a phone-in-hand result, and I'm not going to write around that.** Every claim above is true of the code and the test suite. Whether it actually feels better under a real thumb, on a real device, mid-conversation — that's the next thing, not a solved thing.

---

## Appendix A — feature surface

- **Core typing** — QWERTY layout, autocorrect, next-word/completion prediction, personal dictionary
- **Gestures** — swipe left to delete a word, swipe up/down to cycle suggestions and punctuation, no glide-typing by design
- **Extensions** — clipboard manager, emoji panel, built on an in-process extension API (`extensions-api` / `extensions-builtin` modules)
- **Themes** — theme editor with presets and custom key colors
- **Update checking** — the only thing that ever touches the network: a periodic, toggleable check against GitHub's public Releases API, never anything else
- **Privacy** — no `INTERNET` permission on the core typing path, no analytics, no ad SDK, GPLv3

## Appendix B — the pivots, in the project's own words

- **v1.0.0 → v2.0.0 (Aug 11–12)** — the first 24 hours: from "replicate Fleksy" to a working gesture-shortcut keyboard, three point releases in one day
- **v2.2.2** — real autocorrect + always-on suggestion "alternatives" rewrite, after user reports on-device
- **v2.3.0 (Aug 29)** — update checking added; the offline-by-default line moved for the first time, deliberately scoped to nothing but a version-number lookup
- **v3.0.0 (Sep 3)** — the alphabetical-suggestion bug found and fixed; noisy-channel correction scoring; personalization rebuilt around knowing-versus-trusting; two features built, tested, and deliberately left switched off

## Appendix C — stack

**Language / UI:** Kotlin, Jetpack Compose, Material 3

**Persistence:** Room (SQLite) for user data; the bundled prediction model is a memory-mapped binary asset, not a database import

**Background work:** WorkManager, for the optional periodic update check

**Architecture:** multi-module — `app` (IME service, settings), `core` (layout, gesture engine, prediction, theming), `extensions-api` / `extensions-builtin` (clipboard, emoji)

**Testing:** JUnit, Robolectric, plus a purpose-built offline evaluation harness for the prediction engine — correction accuracy, false-correction rate, and next-word recall, tracked per release rather than asserted once

**Distribution:** signed release APKs via GitHub Releases, GPLv3, not yet on the Play Store
