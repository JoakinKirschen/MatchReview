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

## 3. Let GitHub Actions build it (optional)

Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `MATCHREVIEW_KEYSTORE_BASE64` | the keystore file as Base64: `base64 -w0 matchreview-release.jks` |
| `MATCHREVIEW_KEYSTORE_PASSWORD` | the store password |
| `MATCHREVIEW_KEY_ALIAS` | `matchreview` |
| `MATCHREVIEW_KEY_PASSWORD` | the key password |

Each run then also uploads an `android-release-apk` artifact. Without the secrets that step is
skipped. The same variables (`MATCHREVIEW_KEYSTORE_FILE` instead of the Base64 one) also work on
any build machine.

## Switching from the debug key

Phones that have a debug APK installed cannot install the release APK over it, because the
signatures differ. Make a backup in the app, uninstall, install the release APK and restore.
