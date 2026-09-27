# Pass 10 notes — hardening and release

Pass 10 completes the planned implementation.

## Reliability and recovery

- App startup now marks orphaned `PREPARING` or `RECORDING` rows as interrupted after an unexpected process/device stop.
- Recording start is guarded in both the Compose camera UI and the foreground service.
- Less than 1 GB free storage displays a warning.
- Less than 250 MB free storage blocks new recordings while still allowing an active recording to be stopped and saved.
- The GitHub Actions build now runs JVM unit tests before assembling the APK.

## Export and privacy

- Match review includes a CSV export through Android's Storage Access Framework.
- Exports contain match metadata, timeline events, player participation intervals, and recording references.
- The UI displays a privacy reminder before export.
- Match deletion requires explicit confirmation and deletes app-created MediaStore recording clips.
- Imported source videos remain owned by the user; the app releases its persisted read grant when the match is deleted.
- Android cloud/device backup and cleartext network traffic are disabled.

## Accessibility

- Camera switching, recording, and torch controls now expose explicit accessibility labels.
- Primary release flows retain text-labelled actions and minimum Material touch targets.
- An instrumentation smoke test checks that the dashboard's primary actions are exposed.

## Verification added

- Storage threshold unit tests.
- CSV escaping and safe-filename unit tests.
- Android migration test for database version 2 to 3.
- Android launch/accessibility smoke test.

Run local JVM tests and build:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Run device/emulator tests:

```bash
./gradlew connectedDebugAndroidTest
```

A physical-device release check should still cover camera permission denial, microphone denial,
low-storage behaviour, device rotation, screen locking, incoming calls, process termination,
and deleting clips on the Android versions supported by the project.
