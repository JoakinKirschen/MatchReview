# Versioning policy

MatchReview uses separate version numbers for code, Android releases, the Room database,
and backup files.

## Source code

Use Git with focused commits. Tag released builds using semantic versions, for example
`v1.1.0`. Keep generated APKs and local signing material out of source control.

## Android app

- Increase `versionCode` for every APK distributed as an update.
- Use `versionName` for the user-facing semantic version:
  - patch: bug fixes;
  - minor: backward-compatible features;
  - major: incompatible product or data changes.

Current release: `versionCode = 2`, `versionName = 1.1.0`.

## Room database

Increase the Room database version only when the database schema changes. Add and test
an explicit migration; do not use destructive fallback for production data.

Current Room database version: `4`.

## Backup format

The backup format is versioned independently in `metadata.json`. Increment it when the
package structure or import contract changes. Restore must reject unsupported future
formats before modifying local data.

Current backup format version: `1`.
