<img src="docs/icon.svg" alt="" width="96" align="right">

# Listening Practice

An Android app for listening practice in Japanese and 32 other languages, built on your own Anki
cards, videos and YouTube.
It plays only sentences made entirely of words you already know, loops the audio, and hides the
text until you ask for it — so you train your ear, not your reading.

## Demo

https://github.com/user-attachments/assets/60efe71b-f156-44d7-ba4a-4adf54268731

One minute with sound: practicing Anki cards, the settings, downloading a YouTube video with
Japanese subtitles, and practicing it line by line. If the player doesn't show (outside
github.com), [open the video directly](https://cdn.jsdelivr.net/gh/mattias/anki-listening-practice@main/docs/demo.mp4).

## Features

- **Uses your Anki deck** — import an AnkiDroid export (`.apkg` deck or `.colpkg` collection).
  Both the default modern format and the "Support older Anki versions" format work.
- **33 languages** — the languages of [Anki Miner](https://github.com/0xzerolight/anki_miner):
  Japanese, Korean, Chinese (Mandarin), Cantonese, English, Catalan, German, Portuguese, French,
  Spanish, Italian, Dutch, Norwegian, Romanian, Greek, Finnish, Hungarian, Croatian, Swedish,
  Polish, Lithuanian, Danish, Turkish, Indonesian, Russian, Arabic, Thai, Persian, Slovenian,
  Ukrainian, Vietnamese and Hebrew — plus Tagalog. Known words, videos, the note type preset and
  the practice source are kept per language. Anki Miner doesn't mine Tagalog, so for Tagalog use
  word lists, videos and YouTube, or cards whose Language field is empty or `tl`.
- **Videos and audio with subtitles** — pick a video or audio file plus subtitles in the language
  you study (`.srt`, `.vtt`, `.ass`). Each subtitle line is one sentence, played in order through
  the file. For Japanese, furigana is generated automatically.
- **YouTube** — paste a link; the app checks for subtitles in your language (manual first, then
  auto-generated), downloads just the audio and subtitles with
  [yt-dlp](https://github.com/yt-dlp/yt-dlp), and adds it like a local file.
- **Only fully known sentences** — sentences are split into words and kept only if every word is
  known: in your word list (from [Jiten](https://jiten.moe) or Anki Miner) or the target word
  (e.g. *Expression*) of one of your cards.
- **Looping audio** — adjustable speed (0.5x–1.5x) and pause between repeats (0–5 s).
- **Audio first** — text, furigana and translation stay hidden until you tap *Show Text*.
  Furigana is drawn above the kanji, from either Anki's `漢字[かんじ]` syntax or HTML `<ruby>`.
  Chinese pinyin and Cantonese jyutping are drawn the same way if your note type has a sentence
  field with them.
- **Queue of 10** — refills automatically when one sentence is left.
- **Three actions**
  - *Crystal Clear*: you understood it; the sentence rests for 10 days.
  - *Skip*: move it to the back of the queue.
  - *Blacklist*: never show it again.
- **Note type presets** for [Anki Miner Note](https://github.com/0xzerolight/anki_miner_note)
  (every language) and the Japanese note types [Kiku](https://github.com/youyoumu/kiku),
  [Lapis](https://github.com/donkuri/lapis) and [Senren](https://github.com/BrenoAqua/Senren),
  with editable field mappings for anything else.
- **Small footprint** — after import only the sentence audio is kept; images, word audio and
  other media are deleted. Your AnkiDroid collection is never modified.

## Install

Download the latest `.apk` from
[Releases](https://github.com/mattias/anki-listening-practice/releases/latest) on your phone and
open it. Android will ask you to allow installing apps from your browser or file manager the first
time. Requires Android 8.0 or newer on a 64-bit ARM phone (practically every phone from the last
several years).

## Getting started

1. **Export from AnkiDroid**: export your mining deck as `.apkg` (or your whole collection as
   `.colpkg`) with *Include media* checked.
2. **Export your known words** as a `.txt` file with one word per line: from Jiten for Japanese,
   or from Anki Miner's *Manage Known Words* for any language (its `.csv` export works too).
3. In the app, open **Settings** and:
   - choose the **language** you study,
   - tap **Import .apkg / .colpkg** and pick your export,
   - tap **Import known_words.txt** and pick your word list,
   - choose the preset matching your note type.
4. Go back and start listening.

Re-import your deck after adding cards. Removing an import in Settings deletes only the app's
copy.

### Videos, audio and YouTube

Under **Settings → Videos & audio**, either tap **Add video/audio + subtitles** and pick the
media file and then its subtitle file, or paste a **YouTube link** and tap **Download from
YouTube**. Then choose the video from the menu at the top of the practice screen. Sentences come
in the order they're spoken; ones with unknown words are skipped.

Picked files stay where they are, so don't move or delete them. YouTube downloads are stored in
the app. Downloading from YouTube is against YouTube's terms of service; use it for your own
study only.

### Field presets

| Preset          | Languages | Note type contains | Sentence   | Furigana           | Translation           | Audio           | Word         | Language   |
|-----------------|-----------|--------------------|------------|--------------------|-----------------------|-----------------|--------------|------------|
| Kiku            | Japanese  | `Kiku`             | `Sentence` | `SentenceFurigana` | `SentenceTranslation` | `SentenceAudio` | `Expression` | —          |
| Lapis           | Japanese  | `Lapis`            | `Sentence` | `SentenceFurigana` | —                     | `SentenceAudio` | `Expression` | —          |
| Senren          | Japanese  | `Senren`           | `sentence` | `sentenceFurigana` | `sentenceTranslation` | `sentenceAudio` | `word`       | —          |
| Anki Miner Note | All       | `Anki Miner Note`  | `Sentence` | `SentenceFurigana` | `SentenceTranslation` | `SentenceAudio` | `Expression` | `Language` |

Kiku, Lapis and Senren are offered only while Japanese is selected; in every other language the
preset is Anki Miner Note. The furigana field exists only for Japanese; for Chinese and Cantonese
it becomes an optional pinyin or jyutping field (empty by default, since Anki Miner puts those on
the word, not the sentence), and other languages have none.

Anki Miner Note holds cards of every language in one note type, so the app reads its `Language`
field (`ja`, `sv`, `zh-Hans`, ...) and practices only cards of the selected language. Cards with
an empty language field count for every language. Anki Miner tags Cantonese cards `zh-Hant`, so
traditional-script Mandarin cards also show up under Cantonese, and Cantonese cards under Chinese.

Field names match case-insensitively. Imports keep only audio referenced by each preset's
audio field, so if you point a preset at a different audio field, re-import your decks.

### How "known" is decided

Known words are your word list for the selected language plus the word field of every imported
card in that language. Punctuation is ignored; particles are not, so they need to be in the list
too.

- **Japanese**: sentences are split with [Kuromoji](https://github.com/atilika/kuromoji). A word
  counts as known if its dictionary form, the form in the sentence, or its kana reading is listed.
- **Other languages**: sentences are split at word boundaries (Android's ICU, which also segments
  Chinese, Cantonese and Thai with a dictionary). Matching ignores case, Arabic, Persian and
  Hebrew vowel marks and Russian/Ukrainian stress marks. There is no dictionary-form lookup, so
  inflected forms (*gehe*, *went*) must be in the list themselves. Unspaced text also counts as
  known if it splits entirely into listed words, French-style elisions (*l'homme*) if both parts
  are listed, and Korean words followed only by particles (*학교에서*) if the word is listed.

### Updates

The app checks GitHub Releases when it starts (turn it off under **Settings → App updates**, or
check by hand there). When a newer version is out it shows the release notes and, on *Update*,
downloads the APK, checks its SHA-256 against GitHub's digest and opens Android's installer. The
first time, Android asks you to allow the app to install updates. Updating keeps your imports and
progress; it only works for release builds signed with the same key as the installed app.

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
Kuromoji (IPADIC), zstd-jni and [youtubedl-android](https://github.com/yausername/youtubedl-android)
(yt-dlp with Python and QuickJS).

## Project layout

```
app/src/main/java/dev/lovkvist/listening/
├── data/anki/       Anki package import (ApkgUnpacker), deck storage (DeckLibrary), presets, text cleanup
├── data/media/      Subtitle parsing, video/audio library, YouTube import
├── data/known/      Known-words list
├── data/local/      Room database for cool-downs and blacklist
├── data/settings/   DataStore settings
├── data/            SentenceRepository: filtering and queue supply
├── text/            Languages, Japanese tokenizer (Kuromoji) and word tokenizer for the rest
├── playback/        Looping player with a pause between repeats
└── ui/              Compose screens and view models
```

## License

[GPL-3.0](LICENSE): use, modify and redistribute freely, including commercially, as long as
what you distribute is also GPL-3.0 with its source code available. The app is GPL because it
bundles youtubedl-android, which is GPL-3.0. Version 0.1.0 was released under MIT.
