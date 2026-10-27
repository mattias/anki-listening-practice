<img src="docs/icon.svg" alt="" width="96" align="right">

# Listening Practice

An Android app for listening practice in Japanese and 33 other languages, built on your own Anki
cards, videos and YouTube.
It plays only sentences made entirely of words you already know, loops the audio, and hides the
text until you ask for it — so you train your ear, not your reading.

## Demo

https://github.com/user-attachments/assets/829b6551-2d81-424b-a8aa-c18002a02e45

A minute and three quarters with sound: practicing a YouTube video line by line, showing the
text with unknown words underlined, tapping words to look them up (furigana, frequency, kanji)
and marking them known, undoing a Crystal Clear, hands-free auto advance, themes, My words,
dictionaries for every language and the setup guide. If the player doesn't show (outside
github.com), [open the video directly](https://cdn.jsdelivr.net/gh/mattias/anki-listening-practice@main/docs/demo.mp4).

## Features

- **Uses your Anki deck** — import an AnkiDroid export (`.apkg` deck or `.colpkg` collection).
  Both the default modern format and the "Support older Anki versions" format work.
- **34 languages** — the languages of [Anki Miner](https://github.com/0xzerolight/anki_miner):
  Japanese, Korean, Chinese (Mandarin), Cantonese, English, Catalan, German, Portuguese, French,
  Spanish, Italian, Dutch, Norwegian, Romanian, Greek, Finnish, Hungarian, Croatian, Swedish,
  Polish, Lithuanian, Danish, Turkish, Indonesian, Russian, Arabic, Thai, Persian, Slovenian,
  Ukrainian, Vietnamese and Hebrew — plus Tagalog and Taiwanese (Taigi, 台語). Anki decks, known
  words, videos, the note type preset and the practice source are kept per language: a deck
  belongs to the language selected when you import it. Star the languages you switch between
  in the language menu to keep them at the top.
- **Taiwanese** — Han characters, Tâi-lô/POJ romanization (hyphenated syllables like *Tâi-gí*)
  or a mix. Han text is split into words like Chinese, and an optional Tâi-lô field shows the
  reading above the characters, like pinyin. Text is drawn with Taiwanese glyph forms.
- **Videos and audio with subtitles** — pick a video or audio file plus subtitles in the language
  you study (`.srt`, `.vtt`, `.ass`). Each subtitle line is one sentence, played in order through
  the file. For Japanese, furigana is generated automatically.
- **YouTube** — search for videos in the app (only ones with captions). YouTube can't search by
  language, so results whose titles are clearly in another language are folded away, and the rest are
  checked and marked: only videos where the language is actually *spoken* count (YouTube translates
  titles and subtitles and dubs audio by machine, so those say nothing; the speech recognition track
  does). Videos spoken in another language, or with captions but no speech, are dimmed. At most ten
  checks per search, one at a time, remembered for a month, to go easy on YouTube. Or paste a link; the app checks for subtitles in your language (manual first, then
  auto-generated), downloads just the audio and subtitles with
  [yt-dlp](https://github.com/yt-dlp/yt-dlp), and adds it like a local file. Subtitles in a second
  language (English by default, with English as the fallback; YouTube's machine translations
  count) become each line's translation.
- **Only fully known sentences** — sentences are split into words and kept only if every word is
  known: in your word list (from [Jiten](https://jiten.moe) or Anki Miner) or the target word
  (e.g. *Expression*) of one of your cards.
  Want more sentences? Under **Settings → Known words** you can allow up to 1–5 unknown words
  per sentence (n+1 to n+5); the default is n+0. The unknown words are then marked in the
  sentence when you show the text, so you can see which word was new.
- **Tap a word** — with the text showing, tap any word to look it up in your dictionaries and
  mark it *Known* or *Ignore*. Known words count as known from then on; ignored ones (names,
  sounds) only stop counting as unknown and aren't exported. They're listed under
  **Settings → My words**, where you can search, switch between known and ignored, or remove them.
- **Explain a sentence** — with the text showing, *Explain* opens the Gemini app with a ready question:
  a translation, the sentence broken down chunk by chunk and every piece of grammar explained, in
  your app's language (the share menu opens instead if Gemini isn't installed).
- **Yomitan dictionaries for every language** — under **Settings → Dictionaries**, install with one
  tap the five dictionaries [Hachidori](https://github.com/bee-san/hachidori) sets up by default
  for Japanese, or for any language a [Wiktionary](https://github.com/yomidevs/wiktionary-to-yomitan)
  dictionary with definitions in the language you choose (your app language by default). Import any
  other Yomitan `.zip` too, and put your dictionaries in the order lookups should show them.
  Dictionaries that publish updates are checked daily and updated by themselves (per dictionary,
  can be turned off). Readings are shown as furigana above the kanji, in headwords and examples.
- **Export known words** — **Settings → Known words → Export** writes your word list, your cards'
  words and the words you marked known to a `.txt` file, one per line, for Jiten or Migaku.
- **Looping audio** — adjustable speed (0.5x–1.5x) and pause between repeats (0–5 s), set from
  the chips under the sentence. A ring around the play button shows how far the sentence (or,
  with auto Crystal Clear, all its plays) has come.
- **Audio first** — the sentence card stays face down until you tap it; then it flips over to
  show the text, furigana and translation.
  Furigana is drawn above the kanji, from either Anki's `漢字[かんじ]` syntax or HTML `<ruby>`.
  Chinese pinyin and Cantonese jyutping are drawn the same way if your note type has a sentence
  field with them.
- **One sentence at a time** — Anki cards come in random order without repeats until you've
  heard them all; a video plays its lines in order. Audio never starts by itself: a new sentence
  keeps playing if you were listening and stays paused if you weren't.
- **Three actions**
  - *Crystal Clear*: you understood it; the sentence rests for 10 days.
  - *Skip*: go to the next sentence; this one comes back later.
  - *Blacklist*: never show it again (after a confirmation). Blacklisted sentences are listed in
    Settings → Progress, where each one can be restored.
- **Chains** — for videos, the *Chain* chip under the sentence repeats several neighboring lines as one
  stretch. **+** adds the next line and **−** drops the last one; **⟨ ⟩** move the whole chain along
  the video (to add a line before the chain, move it back and press +), and **✕** turns it off. The
  arrows point the way the language is read (left for Arabic, Persian and Hebrew). Crystal Clear and
  Skip act on all the chain's lines and count each of them in the day's stats; Undo takes back
  exactly what that action counted.
- **Undo** — the back arrow next to the actions returns to the sentence you just heard and takes
  back what you did to it (Crystal Clear, Skip or Blacklist, and the day's stats), several steps back.
- **Hands-free** — move on automatically after 3, 5, 10 or 20 plays: each sentence repeats that
  many times, then counts as Crystal Clear (or is skipped, if you choose *Skip*) and the next one
  starts, so you can listen on the go.
- **Playback defaults** — speed, pause, auto advance and the headset double tap also have their own
  page under **Settings → Playback**.
- **Sleep timer** — pause playback after 10 to 60 minutes, from the playback settings (the
  chips under the sentence); the time left shows under the play button.
- **Headset and notification controls** — play/pause from your headset button; double-tap it
  (or press *next*) to skip, *previous* plays the sentence again. The double tap can mark the
  sentence Crystal Clear instead (in the playback settings). While audio plays, a media
  notification has play/pause, replay, skip and Crystal Clear, and practice keeps going with the
  screen off.
- **Pauses for calls** — audio pauses while the phone rings or you're in a call (also app calls)
  and resumes afterwards; it also pauses when headphones are unplugged.
- **Any note type** — cards are read by their field names, not their note type: every card with
  the sentence and sentence audio fields is used. Presets fill in the field names of
  [Anki Miner Note](https://github.com/0xzerolight/anki_miner_note) (every language) and the
  Japanese note types [Kiku](https://github.com/youyoumu/kiku),
  [Lapis](https://github.com/donkuri/lapis) and [Senren](https://github.com/BrenoAqua/Senren);
  edit them for your own note type.
- **Themes** — Material You (your wallpaper's colors, light or dark) or one of 19 palettes after
  [daisyUI](https://daisyui.com)'s themes, like the ones Kiku offers: cupcake, nord, dracula,
  synthwave, coffee and more. Pick one under **Settings → Appearance**.
- **Daily stats** — sentences practiced today, how many were Crystal Clear, and your streak of
  days in a row.
- **Your language** — the app itself is in English and 32 other languages (the study languages,
  with Traditional Chinese for Cantonese and Taiwanese). It follows the phone's language; change
  it under **Settings → App language** (or Android 13+'s per-app language setting). The
  translations are machine-made; corrections are welcome.
- **Small footprint** — after import only the sentence audio is kept; images, word audio and
  other media are deleted. Your AnkiDroid collection is never modified.

## Install

Download the latest `.apk` from
[Releases](https://github.com/mattias/anki-listening-practice/releases/latest) on your phone and
open it. Android will ask you to allow installing apps from your browser or file manager the first
time. Requires Android 8.0 or newer on a 64-bit ARM phone (practically every phone from the last
several years).

## Getting started

The first time you open the app (also after updating from a version without it), a setup guide
walks you through the steps below: app language, the language you study, dictionaries, known
words and your Anki deck. You can open it again under **Settings → Setup guide**.

1. **Export from AnkiDroid**: export your mining deck as `.apkg` (or your whole collection as
   `.colpkg`) with *Include media* checked.
2. **Export your known words** as a `.txt` file with one word per line: from Jiten for Japanese,
   or from Anki Miner's *Manage Known Words* for any language (its `.csv` export works too).
3. In the app, open **Settings** and:
   - choose the **language** you study,
   - tap **Import .apkg / .colpkg** and pick your export (its cards count as that language),
   - tap **Import known_words.txt** and pick your word list,
   - under **Field preset**, choose the preset for your note type, or edit the field names to
     match your own.
4. Go back and start listening.

Re-import your deck after adding cards. Removing an import in Settings deletes only the app's
copy.

### Videos, audio and YouTube

Under **Settings → Videos & audio**, either tap **Add video/audio + subtitles** and pick the
media file and then its subtitle file, or search YouTube (the results show which videos have subtitles in your language; tap one to download it), or
paste a **YouTube link** and tap **Download from YouTube**. Then choose the video from the menu at the top of the practice screen. Sentences come
in the order they're spoken; ones with unknown words are skipped.

YouTube downloads also fetch subtitles in the language under **Translations from** (English by
default; if a video has none in that language, English; *Off* skips them) and show them as the
translation of each line. Lines are matched by time, so a translation can cover a little more or
less than its line.

Picked files stay where they are, so don't move or delete them. YouTube downloads are stored in
the app. Downloading from YouTube is against YouTube's terms of service; use it for your own
study only.

### Field presets

| Preset          | Languages | Sentence   | Furigana           | Translation           | Audio           | Word         |
|-----------------|-----------|------------|--------------------|-----------------------|-----------------|--------------|
| Kiku            | Japanese  | `Sentence` | `SentenceFurigana` | `SentenceTranslation` | `SentenceAudio` | `Expression` |
| Lapis           | Japanese  | `Sentence` | `SentenceFurigana` | —                     | `SentenceAudio` | `Expression` |
| Senren          | Japanese  | `sentence` | `sentenceFurigana` | `sentenceTranslation` | `sentenceAudio` | `word`       |
| Anki Miner Note | All       | `Sentence` | `SentenceFurigana` | `SentenceTranslation` | `SentenceAudio` | `Expression` |

A preset only fills in field names; the note type's name doesn't matter. Every card whose note
type has the sentence field and the sentence audio field (with a `[sound:…]` file) is used, so a
note type of your own works once its field names are filled in. The other fields are optional.

Kiku, Lapis and Senren are offered only while Japanese is selected; in every other language the
preset is Anki Miner Note. The furigana field exists only for Japanese; for Chinese and Cantonese
it becomes an optional pinyin or jyutping field (empty by default, since Anki Miner puts those on
the word, not the sentence), and other languages have none.

Every card in a deck counts as the deck's language, whatever its fields say. Anki Miner Note
holds cards of every language in one note type, so export each language's deck separately and
import it with that language selected. Decks imported before version 0.5.0 get the language most
of their cards name in a `Language` field, or Japanese.

Field names match case-insensitively. Imports keep only audio referenced by each preset's
audio field, so if you point a preset at a different audio field, re-import your decks.

### How "known" is decided

Known words are your word list for the selected language, the word field of every imported card
in that language, and the words you marked known. Punctuation is ignored; particles are not, so they need to be in the list
too.

With n+1 to n+5 set, a sentence may also have up to that many different unknown words (a word
that appears twice counts once).

- **Japanese**: sentences are split with [Kuromoji](https://github.com/atilika/kuromoji). A word
  counts as known if its dictionary form, the form in the sentence, or its kana reading is listed.
- **Other languages**: sentences are split at word boundaries (Android's ICU, which also segments
  Chinese, Cantonese and Thai with a dictionary). Matching ignores case, Arabic, Persian and
  Hebrew vowel marks and Russian/Ukrainian stress marks. There is no dictionary-form lookup, so
  inflected forms (*gehe*, *went*) must be in the list themselves. Unspaced text also counts as
  known if it splits entirely into listed words, French-style elisions (*l'homme*) if both parts
  are listed, and Korean words followed only by particles (*학교에서*) if the word is listed.

### Dictionaries

The recommended Japanese dictionaries are the ones Hachidori installs by default, downloaded
from their authors:

- [Jitendex](https://jitendex.org) — Japanese–English
- [JMnedict](https://github.com/yomidevs/jmdict-yomitan) — names
- [Bee's Ultimate Kanji Dictionary](https://github.com/bee-san/bees-ultimate-kanji-dictionary)
- [Jiten](https://jiten.moe) frequency list
- [Bee's Ultimate Grammar Dictionary](https://github.com/bee-san/bees-ultimate-grammar-dictionary)

Each is under its own license.

For every language there are dictionaries built from Wiktionary by
[wiktionary-to-yomitan](https://github.com/yomidevs/wiktionary-to-yomitan) (data from
[Kaikki](https://kaikki.org), CC BY-SA). Each covers the words of one language with definitions
from one Wiktionary edition (English, German, French, Japanese and more); the app suggests your
app language if there is one, else English. Inflected forms lead to their base form (*dricker* →
*dricka*). Chinese dictionaries serve Mandarin, Cantonese and Taiwanese, and Croatian uses the
Serbo-Croatian one.

Any other [Yomitan](https://github.com/yomidevs/yomitan) dictionary can be imported as a `.zip`;
it belongs to the language you study when you import it, like decks. Lookups show dictionaries in
the order under **Your dictionaries** (move them with the arrows). The dictionaries are stored in
the app; removing one frees the space.

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
WorkManager, Gson,
Kuromoji (IPADIC), zstd-jni and [youtubedl-android](https://github.com/yausername/youtubedl-android)
(yt-dlp with Python and QuickJS).

## Project layout

```
app/src/main/java/dev/lovkvist/listening/
├── data/anki/       Anki package import (ApkgUnpacker), deck storage (DeckLibrary), presets, text cleanup
├── data/media/      Subtitle parsing, video/audio library, YouTube import
├── data/known/      Known-words list and the words you marked
├── data/dict/       Yomitan dictionaries: reader, storage, lookup, downloads and daily updates
├── data/local/      Room database for cool-downs, blacklist, daily stats and marked words
├── data/settings/   DataStore settings
├── data/            SentenceRepository: filtering and picking the next sentence
├── l10n/            App language picker, translated strings outside the UI, localized language names
├── text/            Languages, Japanese tokenizer (Kuromoji) and word tokenizer for the rest
├── playback/        Looping player, practice session, media session service (notification, headset)
└── ui/              Compose screens and view models
```

## License

[GPL-3.0](LICENSE): use, modify and redistribute freely, including commercially, as long as
what you distribute is also GPL-3.0 with its source code available. The app is GPL because it
bundles youtubedl-android, which is GPL-3.0. Version 0.1.0 was released under MIT.

Icons are [Material Icons](https://github.com/google/material-design-icons) (Apache 2.0). Theme
colors are modeled on [daisyUI](https://github.com/saadeghi/daisyui) (MIT).
