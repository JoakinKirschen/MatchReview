# Pass 14 — Encrypted backup and restore

## Included

- A **Backup & restore** screen reachable from the dashboard and match review.
- Android Storage Access Framework integration through `CreateDocument` and `OpenDocument`.
- Password-encrypted `.mrbak` files using:
  - PBKDF2-HMAC-SHA256 key derivation;
  - a random 16-byte salt;
  - AES-256-GCM authenticated encryption;
  - 150,000 PBKDF2 iterations.
- A compressed ZIP payload with:
  - `metadata.json`;
  - `database.json`;
  - `media_manifest.json`;
  - optional video files.
- Metadata for backup-format version, Room database version, app version, and UTC creation time.
- Full export of the ten Room tables.
- Transactional replace-on-restore so a failed database import rolls back.
- Validation before replacement, including supported versions and required tables.
- Restore protection while a match or recording is active.
- Explicit confirmation before existing data is replaced.
- Optional video inclusion; videos remain excluded by default.
- Restored packaged videos are copied into app-private storage and their URI references are remapped.
- Warnings when referenced media cannot be read.
- JVM tests for encryption round trips and absence of plaintext payload data.

## Cloud behaviour

MatchReview has no cloud account, server, background sync, or vendor lock-in. Android's
document picker delegates storage to providers installed by the user. A backup can
therefore be stored in Google Drive, OneDrive, another document provider, or local
storage without giving MatchReview direct access to a cloud account.

## Privacy and recovery

All backups require a password of at least six characters. The password is not stored
by MatchReview and cannot be recovered. Video backup remains opt-in because recordings
may contain identifiable people and can create very large files.

## App version

- `versionName`: `1.1.0`
- `versionCode`: `2`
- Room database: `4` (unchanged; backup does not alter the entity schema)
- Backup format: `1`

## Verification

```bash
./gradlew testDebugUnitTest assembleDebug
```

Physical-device tests should cover local files, the club's chosen document provider,
large video packages, incorrect passwords, damaged files, and process interruption.
