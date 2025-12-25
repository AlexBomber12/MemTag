# MemTag

MemTag is an Android app targeting Chainway C72 (Android 8) with a Compose + Material 3 UI foundation.

## Windows-first prerequisites
- Windows 10/11
- Android Studio (Hedgehog or newer recommended) with bundled JBR
- Android SDK with API 26+ installed
- If you need JAVA_HOME in PowerShell:
  - `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`
  - `$env:PATH="$env:JAVA_HOME\bin;$env:PATH"`

## Android Studio workflow
1. Open the project in Android Studio.
2. Let Gradle Sync complete.
3. Run the `app` configuration on a Chainway C72 (or any Android 8+ device/emulator).

## Configure Memento settings
Open **Settings** in the app and set:
- **Token** (Memento token)
- **Library ID** (Memento library)

These values are persisted with DataStore and used across app restarts.

## Queue CSV (Find/Geiger)
- Accepted formats:
  - One EPC per line
  - CSV with header where the first column is `EPC` (case-insensitive)
  - CSV with multiple columns (first non-empty cell is treated as the EPC)
- EPC normalization rules:
  - Trim, remove spaces/line breaks, uppercase
  - Hex only, length 8..64
- Import behavior:
  - Duplicates are skipped (within the file and against the queue)
  - Invalid rows are reported with row numbers
- Export columns:
  - `EPC`, `Status`, `UpdatedAt` (ISO-8601 local time), `Note`
- Suggested workflow: import CSV, start Find on the current item, mark status, export results.

## Local PowerShell commands
```powershell
.\gradlew.bat --no-daemon clean :app:assembleMockDebug :app:testMockDebugUnitTest :app:lintMockDebug
.\gradlew.bat :app:testMockDebugUnitTest
.\gradlew.bat :app:lintMockDebug
.\gradlew.bat ktlintCheck
.\gradlew.bat :app:assembleMockDebug
.\gradlew.bat :app:assembleDeviceDebug
```

## CI overview
GitHub Actions runs on `windows-latest`, builds/tests the mock flavor, and uploads the mock debug APK artifact.

## RFID notes
See `docs/RFID_NOTES.md` for hardware build steps, vendor SDK placement, and diagnostics checklist.
