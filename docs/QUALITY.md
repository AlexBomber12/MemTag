# Quality Contract

The real quality gate is `scripts/android-ci.ps1 -Mode ci`.

Inspect Code is informational and uses the `MemTag-Final` profile.
Spellchecking typos are disabled intentionally to avoid infinite noise from abbreviations, tech terms, paths, and logs.
Dependency-update lint checks `NewerVersionAvailable` and `GradleDependency` are ignored intentionally to avoid infinite churn.

Build commands:
- `./gradlew :app:lintMockDebug`
- `./gradlew :app:testMockDebugUnitTest`
- `./gradlew ktlintCheck`
- `./gradlew :app:assembleDeviceRelease`

APKs appear under `app/build/outputs/apk/...`.
