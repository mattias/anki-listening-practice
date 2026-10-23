# Listening Practice

An Android app for Japanese listening practice built on your own Anki cards. It plays only
sentences made entirely of words you already know, loops the audio, and hides the text until
you ask for it — so you train your ear, not your reading.

## Features

- **Uses your Anki deck** — import an AnkiDroid export (`.apkg` deck or `.colpkg` collection).
  Both the default modern format and the "Support older Anki versions" format work.
- **Only fully known sentences** — sentences are split into words with
  [Kuromoji](https://github.com/atilika/kuromoji) and kept only if every word is in your
  known-words list exported from [Jiten](https://jiten.moe).
- **Looping audio** — adjustable speed (0.5x–1.5x) and pause between repeats (0–5 s).
- **Audio first** — text, furigana and translation stay hidden until you tap *Show Text*.
  Furigana is drawn above the kanji, from either Anki's `漢字[かんじ]` syntax or HTML `<ruby>`.
- **Queue of 10** — refills automatically when one sentence is left.
- **Three actions**
  - *Crystal Clear*: you understood it; the sentence rests for 10 days.
  - *Skip*: move it to the back of the queue.
  - *Blacklist*: never show it again.
- **Note type presets** for [Kiku](https://github.com/youyoumu/kiku),
  [Lapis](https://github.com/donkuri/lapis) and [Senren](https://github.com/BrenoAqua/Senren),
  with editable field mappings for anything else.
- **Small footprint** — after import only the sentence audio is kept; images, word audio and
  other media are deleted. Your AnkiDroid collection is never modified.

## Install

Download the latest `.apk` from
[Releases](https://github.com/mattias/anki-listening-practice/releases/latest) on your phone and
open it. Android will ask you to allow installing apps from your browser or file manager the first
time. Requires Android 8.0 or newer.

## Getting started

1. **Export from AnkiDroid**: export your mining deck as `.apkg` (or your whole collection as
   `.colpkg`) with *Include media* checked.
2. **Export known words from Jiten** as a `.txt` file with one word per line.
3. In the app, open **Settings** and:
   - tap **Import .apkg / .colpkg** and pick your export,
   - tap **Import known_words.txt** and pick your word list,
   - choose the preset matching your note type.
4. Go back and start listening.

Re-import your deck after adding cards. Removing an import in Settings deletes only the app's
copy.

### Field presets

| Preset | Note type contains | Sentence  | Furigana           | Translation           | Audio           |
|--------|--------------------|-----------|--------------------|-----------------------|-----------------|
| Kiku   | `Kiku`             | `Sentence`| `SentenceFurigana` | `SentenceTranslation` | `SentenceAudio` |
| Lapis  | `Lapis`            | `Sentence`| `SentenceFurigana` | —                     | `SentenceAudio` |
| Senren | `Senren`           | `sentence`| `sentenceFurigana` | `sentenceTranslation` | `sentenceAudio` |

Field names match case-insensitively. Imports keep only audio referenced by each preset's
audio field, so if you point a preset at a different audio field, re-import your decks.

### How "known" is decided

A word counts as known if its dictionary form, the form in the sentence, or its kana reading is
in your list. Punctuation is ignored; particles are not, so they need to be in the list too.

## Building

Requirements: JDK 17 and the Android SDK (platform 35). Set `sdk.dir` in `local.properties`
or `ANDROID_HOME`.

```bash
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
./gradlew installDebug         # install on a connected device
```

Minimum Android version is 8.0 (API 26).

To build a signed release (`./gradlew assembleRelease`), create a git-ignored
`keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. Without it the release APK is left unsigned.

## Tech stack

Kotlin, Jetpack Compose (Material 3), Hilt, Coroutines/Flow, Media3 ExoPlayer, Room, DataStore,
Kuromoji (IPADIC) and zstd-jni.

## Project layout

```
app/src/main/java/dev/lovkvist/listening/
├── data/anki/       Anki package import (ApkgUnpacker), deck storage (DeckLibrary), presets, text cleanup
├── data/known/      Known-words list
├── data/local/      Room database for cool-downs and blacklist
├── data/settings/   DataStore settings
├── data/            SentenceRepository: filtering and queue supply
├── text/            Japanese tokenizer (Kuromoji)
├── playback/        Looping player with a pause between repeats
└── ui/              Compose screens and view models
```

## License

[MIT](LICENSE): use, modify and redistribute freely, including commercially. Just keep the
copyright notice.
