# Release signing

Debug builds use the shared `app/debug.keystore`, which is fine for testing but must never be
used for a store release: anyone with the repository could sign an update. A release build
is signed with **your own private key**, which never goes into Git.

## 1. Create the key once

```bash
keytool -genkeypair -v -keystore matchreview-release.jks -alias matchreview \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` file and both passwords in a password manager **and** a second safe place.
If the key is lost, the app can no longer be updated on the Play Store under the same listing
(unless Play App Signing holds the app signing key, which is recommended).

## 2. Build a signed release on your computer

Create `keystore.properties` next to `settings.gradle.kts` (it is in `.gitignore`):

```properties
storeFile=/full/path/to/matchreview-release.jks
storePassword=your-store-password
keyAlias=matchreview
keyPassword=your-key-password
```

Then:

```bash
./gradlew assembleRelease
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`. Without
`keystore.properties` (or the environment variables below) the release APK is left unsigned.

## 3. Let GitHub Actions build it (needed for the Play Store bundle)

Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `MATCHREVIEW_KEYSTORE_BASE64` | the keystore file as Base64 (see below) |
| `MATCHREVIEW_KEYSTORE_PASSWORD` | the store password |
| `MATCHREVIEW_KEY_ALIAS` | the key alias, for example `matchreview` |
| `MATCHREVIEW_KEY_PASSWORD` | the key password |

The keystore as Base64, copied to the clipboard:

- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\full\path\matchreview-release.jks")) | Set-Clipboard`
- macOS: `base64 -i matchreview-release.jks | pbcopy`
- Linux: `base64 -w0 matchreview-release.jks`

Each run then also uploads an `android-release-apk` artifact and an `android-play-bundle`
artifact (the `.aab` for the Play Store). Without the secrets those steps are skipped. The same
variables (`MATCHREVIEW_KEYSTORE_FILE` instead of the Base64 one) also work on any build machine.

## Publishing on the Play Store, step by step

1. **Use the right key.** If you already uploaded a bundle to Play Console, use the same
   keystore file and passwords: Play only accepts updates signed with the upload key it knows.
   Lost it? Play Console → your app → *Test and release → App integrity → App signing* →
   *Request upload key reset*. Uploading for the first time? Create a key without the command
   line in Android Studio: *Build → Generate Signed App Bundle or APK → Android App Bundle →
   Create new…*, then cancel the wizard once the `.jks` file is saved.
2. **Store it in GitHub** as the four secrets above.
3. **Build:** on GitHub, *Actions → Android Build → Run workflow*, branch `master`, tick
   *Publish a GitHub release*. When the release of this version already exists, the run adds
   `MatchReview-<version>.aab` and the release-signed APK to it.
4. **Upload:** download `MatchReview-<version>.aab` from the release and upload it in Play
   Console (*Test and release*, choose a track, *Create new release*). Each upload needs a higher
   `versionCode` than the last one in Play Console.

## Switching from the debug key

Phones that have a debug APK installed cannot install the release APK over it, because the
signatures differ. Make a backup in the app, uninstall, install the release APK and restore.
