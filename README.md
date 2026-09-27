# MatchReview Android

A native Android application for offline football match review, team/player management,
video playback, event tagging, and match notes.

## Build with GitHub Actions

1. Create a new GitHub repository.
2. Unzip this package.
3. Open the extracted project folder and upload **its contents**—including the hidden
   `.github` folder—to the repository root. Do not add an extra enclosing folder.
4. Commit to the `main` branch.
5. Open **Actions → Build Android APK → Run workflow**.
6. When the build finishes, download the `matchreview-debug-apk` artifact.

The workflow installs Java 17 and Android API 35/build-tools 35.0.0, and builds with the
Gradle wrapper (Gradle 8.9). It runs the unit tests and fails if any test fails. It always
uploads the `gradle-logs` and `android-test-reports` artifacts, even if compilation fails.



### Troubleshooting: “Plugin com.android.application was not found”

This package declares the Android and Kotlin plugin versions in both the root
`build.gradle.kts` and the `pluginManagement.plugins` block in `settings.gradle.kts`.
If GitHub Actions still reports that the Android plugin has no version, check that:

1. `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, and the `app` folder are all at
   the repository root;
2. the repository does not contain an older root `build.gradle.kts` that starts with a
   versionless `id("com.android.application")`; and
3. the workflow is building the same directory whose `settings.gradle.kts` you uploaded.

The workflow prints the selected Gradle project path before validation.

## Important project files

- `build.gradle.kts`: declares Android/Kotlin plugin versions.
- `settings.gradle.kts`: declares plugin versions plus plugin and dependency repositories.
  Plugin versions are intentionally present here as a fallback for versionless module
  declarations.
- `app/build.gradle.kts`: Android app and dependency configuration.
- `.github/workflows/build-apk.yml`: APK build workflow.
- `VERSIONING.md`: Git, Android, Room, and backup-format version policy.

## Toolchain

- Android Gradle Plugin 8.6.1
- Gradle 8.9 (via the committed Gradle wrapper)
- Kotlin 1.9.24
- Java 17
- compileSdk / targetSdk 35
- Compose compiler 1.5.14

## Local build

If Android SDK 35 is installed:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The unit tests include Robolectric tests for the Room DAO, the full 1→4 database
upgrade, and long-press drag-and-drop on the lineup builder. They run on the JVM; no
emulator is needed.

The APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```


## Current implementation status

This package contains the **10-pass implementation plan plus three post-plan enhancement
passes (11–14)**. It includes release hardening, exports, storage safeguards,
accessibility improvements, privacy-safe deletion, team/player editing, legal match-size
formations, improved layouts, previous-match defaults, PDF summaries, and encrypted backup/restore.

See [`PASS_PLAN.md`](PASS_PLAN.md) for the original phased plan and the post-plan
enhancements, including the backup pass. Existing version-1 databases are upgraded with explicit Room migrations;
destructive migration is not enabled.


## Pass 2 workflow

1. Create a team and add players.
2. Create a match.
3. The app opens **Select match-day squad**.
4. Mark players available, injured, suspended or unavailable.
5. Select the players who are in the squad.
6. Save the squad. Pitch placement follows in Pass 3.


## Pass 3
The match setup now continues from squad selection to a responsive pitch lineup builder with automatic formation placement, a substitutes bench and persisted player positions. See `PASS_3_NOTES.md`.


## Pass 4
Long-press any selected player and drag them between the substitutes bench and pitch. Pitch positions are saved after release, and nearby free formation slots snap automatically. Tap-based and accessibility-safe placement remain available. See `PASS_4_NOTES.md`.


## Pass 5 live match workflow

After saving the starting lineup, choose **Continue to match day**. Kick off the first period, pause/resume the durable clock, end periods, and start the next period. If you leave the screen, use **Resume live match** on the dashboard.


## Pass 6

Live substitutions, player removal, rolling re-entry, and accurate participation intervals are now implemented. See `PASS_6_NOTES.md`.


## Pass 7 additions

The live match screen now supports goals, assists, opponent goals, score correction,
undo, and a persisted event timeline. The displayed score is kept consistent with
the stored scoring events.


## Pass 8 camera recording

Open the **Camera** tab during match day, grant camera permission, choose whether audio
should be recorded, and tap the red record control. Each recording is saved as a separate
MediaStore video in `Movies/MatchReview` and tracked in Room. Recording can continue while
switching to the Match or Timeline tab.


## Pass 9 video/event workflow

Events created while recording are linked to their video segment automatically. In the
Camera tab, use the goal, substitution and opponent quick actions without leaving the preview.
In Timeline, tap **Clip** to open the correct recording at the event timestamp and navigate
to the previous or next recorded event.


## Pass 10 release hardening

The match review screen can export match data as CSV and permanently delete a match together
with app-recorded clips after confirmation. Imported videos are not deleted. Camera recording
warns below 1 GB free storage and is blocked below 250 MB. Android backup and cleartext traffic
are disabled.

See [`PASS_10_NOTES.md`](PASS_10_NOTES.md) for verification commands and the physical-device
release checklist.


## Pass 11 team, player, and match-size improvements

Teams and players can be edited or removed with confirmation. Match setup provides 3v3,
5v5, 8v8, and 11v11 presets with legal formations. The 8v8 choices include **2-4-1**.
Lineup players continue to support long-press drag-and-drop plus tap-based and
accessibility-safe placement. See [`PASS_11_NOTES.md`](PASS_11_NOTES.md).

## Pass 12 layout and drag reliability

The lineup editor removes the scrolling parent that previously competed with drag input.
Players are moved by **long-pressing and then dragging** between the bench and pitch.
Tap-based placement remains available. Both lineup and live-match pitches use the
available screen space more efficiently. See [`PASS_12_NOTES.md`](PASS_12_NOTES.md).


## New match defaults and PDF summaries

When creating a match, MatchReview now reuses the previous match's competition, match
size, formation, and period timing. Formation entry is restricted to legal choices for
the selected match size. The review screen can also save a printable PDF match summary
in addition to the detailed CSV export.



## Pass 14 encrypted backup and restore

Open **Backup & restore** from the dashboard or from a match review. MatchReview creates
a password-encrypted `.mrbak` package containing versioned JSON for all teams, players,
matches, squads, lineups, events, periods, minutes, and recording references. Android's
document picker can save the file locally or through an installed provider such as
Google Drive or OneDrive; MatchReview itself does not connect to a cloud account.

Video files are excluded by default. They can be included explicitly, with a warning
that the backup may be large and that club consent and retention rules still apply.
Restore validates the encrypted package before replacing data, requires a confirmation,
and is blocked while a match or recording is active. A wrong password or damaged package
does not alter the existing database.

The backup format records its own format version, the Room database version, the app
version, and creation time. Versions `1.1.x` use backup format `1`. See
[`PASS_14_NOTES.md`](PASS_14_NOTES.md) and [`VERSIONING.md`](VERSIONING.md).

## Launcher icon

An adaptive **Pitch Replay** launcher icon is included, with round-mask and Android 13+
themed-icon support. Two alternative concepts are documented in
[`ICON_SUGGESTIONS.md`](ICON_SUGGESTIONS.md).
