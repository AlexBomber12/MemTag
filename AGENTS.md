# AGENTS

## Workflow Split
- Use Cursor + Codex for code generation, refactors, multi-file edits, and unit tests.
- Use Android Studio for Gradle Sync, running on emulator/device, debugging, and profiling.
- Do not assume Android Studio can be driven by the agent. The agent should only modify repo files.

## Environment Constraints
- Windows only. Do not use WSL, bash, apt-get, sudo.
- Run Gradle via PowerShell using `.\gradlew.bat` (never `./gradlew`).
- Use Java from Android Studio JBR. If JAVA_HOME is needed, set it to:
  - `C:\Program Files\Android\Android Studio\jbr`
- Commands must be Windows-compatible.

## Standard Commands (PowerShell, repo root)
- `.\gradlew.bat :app:testMockDebugUnitTest`
- `.\gradlew.bat :app:lintMockDebug`
- `.\gradlew.bat :app:ktlintCheck`
- `.\gradlew.bat :app:assembleMockDebug`

## Device Flavor Commands (vendor SDK required)
- Device flavor tasks require vendor SDK jars/aars in `app/libs` or `app/lib`.
- Run only when the SDK files are present locally:
  - `.\gradlew.bat :app:verifyDeviceLibs`
  - `.\gradlew.bat :app:assembleDeviceDebug`

## If JAVA_HOME Is Required in the Current Shell
- `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
- `$env:PATH="$env:JAVA_HOME\bin;$env:PATH"`

## Architecture and Style Rules
- Kotlin only.
- UI in Jetpack Compose + Material 3.
- Do not add new dependencies unless explicitly requested in the task.
- Keep modules and packages tidy and predictable:
  - ui (screens, components, theme)
  - data (settings store, repositories)
  - domain (use-cases)
  - integrations (memento, uhf, scan2d placeholders in early PRs)
- Do not log secrets. Never print the Memento token in logs. Mask it in UI diagnostics.

## Project-Specific Notes
- Target device is Chainway C72 running Android 8.
- minSdk must be 26.
- For PR-01, do not integrate vendor SDKs yet. Create interfaces/placeholders only.

## Definition of Done for PR-01
- App launches without crashes.
- Navigation works across all screens.
- Settings are persisted and restored.
- CI runs Windows-friendly Gradle commands and passes.
