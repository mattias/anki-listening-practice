# Statistics and the Immersion Dashboard

The app counts what you practice, per day, study language and kind of source, and shows it under **Settings → Statistics**: listening
time, sentences, plays, Crystal Clear and days practiced, for today, this week, this month, this year or all time, with a chart over
time, a list per language and per source, and a language filter.

## What is counted

| | |
|---|---|
| **Listening time** | Time the audio of a sentence is heard, as long as it really takes (at the speed it plays). Pauses between repeats, paused playback and the silence of a sentence's gap are not counted. |
| **Active time** | Time playback is going at all, pauses between repeats included. As `active` in the data. |
| **Sentences** | Sentences finished: Crystal Clear, skipped or blacklisted (a chain counts every line). Undo takes it back. |
| **Plays** | Each time a sentence is played, repeats included. |
| **Crystal Clear** | Of the sentences, those marked Crystal Clear. |

Kinds of source: `anki` (cards), `video` (videos and audio), `song`, `sound` (sound training). Days from before the statistics were kept
per language and source have only sentence counts; they are listed as Japanese, kind `other`.

## The data

One entry per local day, study language (`ja`, `sv`, ...) and kind, with **totals**, not increments. Days with nothing counted are left out.

| Field | |
|---|---|
| `date` | `YYYY-MM-DD`, local time |
| `language` | the study language's code |
| `kind` | `anki`, `video`, `song`, `sound` or `other` |
| `sentences`, `plays`, `clear` | counts |
| `seconds` | listening time in seconds (one decimal) |
| `active` | active time in seconds |

**Export** (Settings → Statistics → *Export statistics*) saves it as a file:

```json
{ "app": "listening-practice", "version": 1, "updated": "2026-11-08T14:00:00Z", "device": "Pixel 10 Pro XL", "entries": [
  { "date": "2026-11-08", "language": "ja", "kind": "anki", "sentences": 12, "plays": 80, "clear": 3, "seconds": 412.3, "active": 600.1 }
] }
```

## For the Immersion Dashboard's Android app

The app sends nothing anywhere. The dashboard's app **fetches** the data from this app and uploads it itself, the way it handles its
other sources. It reads a read-only content provider:

```
content://dev.lovkvist.listening.statistics/entries            all rows
content://dev.lovkvist.listening.statistics/entries?since=2026-10-01   rows from that day on
```

The columns are `date`, `language`, `kind`, `sentences`, `plays`, `clear`, `seconds` and `active`, as above. The numbers up to the last
few seconds of listening are included. If this app is not running, Android starts it for the query.

**The reader needs the permission** `dev.lovkvist.listening.permission.READ_STATISTICS`. It is declared by this app as *dangerous*, so the
user is asked once, in Android's own dialog ("Allow Immersion Dashboard to read your Listening Practice statistics?"), and can take it back
in the app settings. A reader that is installed **before** this app cannot be granted it until it is installed again, so the dashboard
should ask for it when the user turns the source on, and treat a `SecurityException` as "not allowed yet".

In the dashboard's app (Java), with the usual run-time permission request first:

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="dev.lovkvist.listening.permission.READ_STATISTICS" />
<queries><provider android:authorities="dev.lovkvist.listening.statistics" /></queries>   <!-- Android 11+ package visibility -->
```

```java
Uri uri = Uri.parse("content://dev.lovkvist.listening.statistics/entries");
try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
    if (c == null) return; // Listening Practice is not installed
    while (c.moveToNext()) {
        String date = c.getString(c.getColumnIndexOrThrow("date"));
        String language = c.getString(c.getColumnIndexOrThrow("language"));
        double seconds = c.getDouble(c.getColumnIndexOrThrow("seconds"));
        // ... one entry per day, language and kind: totals, so a newer row replaces an older one
    }
}
```

Rows are totals: reading again gives the same days with higher numbers, so the dashboard replaces its copy of a day. The dashboard is for
Japanese: it can keep the rows with `language = ja`.
