# RFID Notes

## Build flavors
- Mock (CI/emulator):
  - `.\gradlew.bat :app:assembleMockDebug`
  - `.\gradlew.bat :app:testMockDebugUnitTest`
  - `.\gradlew.bat :app:lintMockDebug`
- Device (Chainway C72):
  - `.\gradlew.bat :app:assembleDeviceDebug`

If you build the device flavor without vendor libraries, Gradle fails fast with a clear message.

## Vendor SDK drop-in
- Place Chainway UHF SDK `.jar` or `.aar` files in `app/libs` (preferred) or `app/lib` (legacy).
- If the SDK includes native `.so` files, place them in `app/src/device/jniLibs/<abi>/`.

## Diagnostics screen checklist (device flavor)
1. Open **Diagnostics** in the app.
2. Tap **Initialize** and verify status updates to initialized.
3. Tap **Read single** and verify a nearby tag EPC appears.
4. Tap **Start inventory**, confirm live readings appear, then **Stop inventory**.
5. Change **Power** and **Region**, verify they apply and persist after app restart.
6. Leave the screen and confirm inventory stops (no continued scanning).
