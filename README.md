<div align="center">

![omakey](docs/omakey.jpg)

# omakey

**The keyboard for people who type like they mean it.**

Fast. Gesture-driven. Nothing you type ever leaves your phone.

[![Latest release](https://img.shields.io/github/v/release/thehumanx/omakey?label=latest%20release)](https://github.com/thehumanx/omakey/releases/latest)
[![Download APK](https://img.shields.io/badge/download-latest%20APK-blue)](https://github.com/thehumanx/omakey/releases/latest)
[![Total downloads](https://img.shields.io/github/downloads/thehumanx/omakey/total?label=downloads)](https://github.com/thehumanx/omakey/releases)

[![GitHub stars](https://img.shields.io/github/stars/thehumanx/omakey?style=flat&label=stars)](https://github.com/thehumanx/omakey/stargazers)
[![Last commit](https://img.shields.io/github/last-commit/thehumanx/omakey?label=last%20commit)](https://github.com/thehumanx/omakey/commits/main)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#getting-started)
[![Languages: EN, ES, FR, PT-BR, NE](https://img.shields.io/badge/languages-EN%20%C2%B7%20ES%20%C2%B7%20FR%20%C2%B7%20PT--BR%20%C2%B7%20NE-informational)](#languages)
[![APK size: 15 MB](https://img.shields.io/badge/APK%20size-15%20MB-lightgrey)](https://github.com/thehumanx/omakey/releases/latest)
[![License: GPL-3.0](https://img.shields.io/github/license/thehumanx/omakey?label=license)](LICENSE)

</div>

---

## Why this exists

I used Fleksy for over a decade: the gestures, the speed, the all-caps layout, the themes. It's no
longer on the Play Store and stopped getting the things I wanted, like new emoji and an image
clipboard. Nothing else I tried felt right, so I built the keyboard I wanted to use.

Every feature here exists because I wanted it on my own phone. It's my daily keyboard, tested by a
handful of Android friends and Reddit volunteers.

## Highlights

- **Fleksy-style gestures.** Swipe to delete words, cycle suggestions, and save words to your dictionary.
- **Real autocorrect** that learns your words, with a "Learned words" list you can search and edit.
- **Undo / redo** for your last several typed or deleted words.
- **Clipboard history** for text and images, with pinning. Passwords are never saved.
- **Floating, one-handed and resizable** keyboard, right from the keyboard.
- **Inline calculator.** Type `12+7=` and `12+7=19` appears in the suggestion strip.
- **Themes.** Light, Dark, Material You accent, a grid layout, and a full custom theme editor.
- **Light on battery.** Around 1% in daily use, where Fleksy took 5–10% for me.

## Gestures

| Gesture | What it does |
|---|---|
| **Swipe left** on any key | Deletes the last word. Hold to keep deleting. |
| **Swipe right** on any key | Inserts a space (off by default). |
| **Swipe up** on the suggestion strip | Accepts the suggestion, or saves your typed word to the dictionary. Again to un-learn it. |
| **Swipe down** on the suggestion strip | Cycles back through alternatives. |
| **Swipe up/down after `. , ! ? ; : ' "`** | Cycles through punctuation instead of words. |
| **Hold or double-tap Shift** | Caps lock. |
| **Long-press and drag the spacebar** | Moves the cursor. |
| **Long-press a letter** | Accents and extra symbols (à, á, ñ…). |
| **Swipe left/right in the emoji panel** | Switches emoji categories. |

Swipe sensitivity is adjustable in Settings. omakey is **not** a glide-typing keyboard: gestures are
shortcuts for actions, and you still tap out each word.

## Languages

English is built in. Spanish, French (AZERTY or QWERTY), Brazilian Portuguese and Nepali are small
optional downloads from Settings → Languages. Missing accents are restored as you type
("cancion" → "canción"). Nepali can be typed in English letters and converted to Devanagari, or on a
Devanagari layout. With more than one language on, a globe button switches between them, and each
language learns its own words.

## Privacy

Nothing you type, copy or teach the keyboard leaves your phone. There's no analytics, no ads, no
cloud sync. Only two things use the network:

- **Update checks** against GitHub Releases, manual or every 12 hours (can be turned off). Nothing
  is downloaded or installed automatically.
- **Language packs**, only when you pick one. Packs are data only and are signature-checked before install.

Clipboard history skips anything an app marks as sensitive, and records nothing in incognito mode or
password fields. You can review, clear or disable it in Settings → Privacy & data.

## Getting started

omakey isn't on the Play Store yet.

1. Download the APK from the **[latest release](https://github.com/thehumanx/omakey/releases/latest)**, or build it with Gradle.
2. Sideload it, allowing installs from your browser or file manager if asked.
3. Enable omakey in **Settings → System → Languages & input → On-screen keyboard**.
4. Switch to it from your current keyboard's switcher.

## Status and roadmap

Current release: **5.0.0**, which added languages. See [CHANGELOG.md](CHANGELOG.md) for the full history.

- **Next:** a second pass on the suggestion engine.
- **Planned:** more languages, voice input.
- **Not planned:** glide typing, GIF search, cloud sync.

It hasn't been tested much on older hardware. Feedback and feature requests are welcome at
omakey@iambishistha.com.

## License

[GPL-3.0](LICENSE). Fork it, modify it, ship your own version. Just keep it open.
