# Pass 15 — Gradle plugin resolution hardening

## Changes

- Added canonical Android and Kotlin plugin versions to
  `pluginManagement.plugins` in `settings.gradle.kts`.
- Retained the explicit root-project plugin versions in `build.gradle.kts`.
- This deliberate duplication makes plugin resolution robust when module plugin
  declarations omit versions and gives clearer protection against an incomplete upload.
- Added build troubleshooting guidance to `README.md`.
- Advanced the Android release identifier to `versionName 1.1.1` and `versionCode 3`.

## Expected project layout

```text
.github/
app/
gradle/
build.gradle.kts
settings.gradle.kts
gradlew
gradlew.bat
```

Upload the **contents** of the extracted project folder to the GitHub repository root.
Do not upload only the `app` folder and do not keep a stale `build.gradle.kts` from an
older repository revision.

## Verification

```bash
./gradlew projects --no-daemon
./gradlew testDebugUnitTest assembleDebug --no-daemon
```

The first command must resolve `com.android.application` before Android compilation can
begin.
