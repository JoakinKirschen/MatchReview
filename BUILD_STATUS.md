# Build status

## Release candidate

- Version name: `1.6.0`
- Version code: `8`
- Includes implementation passes 1–66
- Room database version `6`
- Backup format is `2` with restore support for legacy format `1`

## Workspace build result

Passes 65–66 were compiled and tested in a cloud workspace with JDK 21, Android SDK 35 and
the Gradle wrapper: all unit tests pass, including the Robolectric screenshot runs
(upright, sideways, tablet and Dutch). GitHub Actions builds every pushed branch.
No real device or emulator was available there; see `RELEASE_CHECKLIST.md`.

## Build locally

Install Android Studio with JDK 17 and Android SDK 35, then run:

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

Expected APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build with GitHub Actions

The included workflow is:

```text
.github/workflows/blank.yml
```

Push the project to GitHub, open **Actions → Android Build and Test → Run workflow**, then
download the `matchreview-debug-apk` artifact.

## Validation completed here

See [`STATIC_VALIDATION_1_4_0.md`](STATIC_VALIDATION_1_4_0.md) for the full checklist.

- Verified Gradle wrapper files are present.
- Verified `versionCode = 6` and `versionName = "1.4.0"`.
- Checked balanced Kotlin delimiters in modified source files.
- Added a DAO test for immediate goal-event ID return.
- No APK is included because it could not be compiled and verified in this environment.

- Added static source validation and focused unit tests for passes 36–45.
