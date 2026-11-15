# MatchReview GitHub Actions workflow update

Copy the included file to this exact repository path:

```text
.github/workflows/android-build.yml
```

The workflow:

- installs Java 17 and Android API 36 / Build Tools 36.0.0;
- validates the Gradle wrapper;
- runs `clean`, `assembleDebug`, and `testDebugUnitTest`;
- captures `--stacktrace`, `--info`, and warning output in `gradle-build.log`;
- uploads diagnostics even when Gradle fails;
- uploads the debug APK only after a successful build;
- marks the workflow failed after diagnostic artifacts have been uploaded.

If another Android build workflow already exists, replace it with this file or disable the older workflow to avoid duplicate runs.
