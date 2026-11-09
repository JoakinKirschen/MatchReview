# MatchReview

An offline Android app for youth football coaches: pick the squad, set the lineup, run
the match from the sideline (clock, goals, substitutions, playing time), film it, and
review it afterwards. Everything stays on the phone; nothing is sent to a server.

Current version: **1.6.0** (`versionCode 8`). Android 8.0 (API 26) or newer.
Languages: English, Dutch, French, German, Spanish, Italian and Turkish, chosen on the home
screen or following the phone's language.

## What it does

**Before the match**
- Teams and players, with logos, shirt numbers and preferred positions.
- Match setup for 3v3, 5v5, 8v8 and 11v11 with legal formations, periods and rolling
  substitutions; the previous match's settings are reused.
- Squad selection with availability (injured, suspended, unavailable).
- Starting lineup by long-press drag and drop on a pitch; a match-day readiness check.

**During the match**
- A match clock that survives app restarts and phone reboots, shown in the notification
  with an alert when the planned period time is up. The notification has *Our goal*,
  *Their goal* and *Pause/Resume* buttons, also on the lock screen.
- Goals (scorer, assist, place in the goal), keeper saves, shots, corners and cards.
- Substitution mode: drag substitutes onto the pitch, confirm the whole round at once.
  Playing minutes per player, with the players who played least highlighted.
- Camera: films the match from the camera tab while the rest of the app keeps working.
  Filming can stop by itself at the end of each period (or when the planned time is up)
  and start again with the next period. A bookmark button marks moments to find later.
- Works upright and held sideways; on tablets the camera sits beside the pitch. The
  *Outdoor* theme gives bigger buttons and stronger contrast in sunlight.

**After the match**
- Timeline, statistics, goal map and lineup pictures; notes and a team rating.
- *Watch full match*: all clips back to back with the match clock, jumps to periods and
  events, slow motion, frame-by-frame stepping and pinch-to-zoom.
- Highlight videos (our goals, all goals, marked moments, one player) and the full match
  as one video file, saved without re-encoding.
- PDF match summary, season report, privacy-safe CSV export.
- Encrypted backups (manual or automatic after every match) and restore.

## Where files are saved

- Match video: the phone's **Movies/MatchReview** folder (visible in the gallery), named
  `MatchReview_<date>_<time>.mp4`; highlight videos go to the same folder.
- Backups: wherever you choose (`.mrbak`, password-encrypted). Automatic backups keep
  the newest five in the chosen folder; *Share latest backup* sends one to Drive or e-mail.

## Build

Android Studio with JDK 17 and Android SDK 35, or the command line:

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Debug builds are signed with the
committed `app/debug.keystore`, so a new APK installs over the previous one and keeps the data.

The unit tests run on the JVM with Robolectric (no emulator). Screenshots of the main
screens, upright, sideways, on a tablet and in Dutch, are rendered into
`app/build/screenshots` with:

```bash
SCREENSHOTS=1 ./gradlew testDebugUnitTest --tests '*ScreenshotTest*'
```

GitHub Actions (`.github/workflows/main.yml`) builds every pushed branch and pull request,
runs the tests and uploads the debug APK as the `matchreview-debug-apk` artifact.

Toolchain: Android Gradle Plugin 8.6.1, Gradle 8.9 (wrapper), Kotlin 1.9.24, Java 17,
compile/target SDK 35, Jetpack Compose, Room, CameraX and Media3.

## Translations

Texts are written in English as `tr("…")`; each language has a table keyed by the English
text in `app/src/main/java/be/matchreview/app/i18n/` (`Dutch.kt`, `French.kt`, `German.kt`,
`Spanish.kt`, `Italian.kt`, `Turkish.kt`). A new language is a new table plus an entry in
`AppLanguage`. Values use named placeholders
(`tr("Period {number}", "number" to 2)`), and a word with two meanings carries a note after
a bar (`tr("Home|navigation")`). `TranslationCoverageTest` fails when a text is missing in any
language, its placeholders differ, or a sentence was left in English.

## Project documents

- [`PASS_CHANGELOG.md`](PASS_CHANGELOG.md): every change, pass by pass (the single source of truth).
- [`VERSIONING.md`](VERSIONING.md): app, database and backup-format versions.
- [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md): checks on a real device before a release.
- [`ICON_SUGGESTIONS.md`](ICON_SUGGESTIONS.md): launcher icon concepts.
