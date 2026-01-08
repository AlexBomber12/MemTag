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

## Find mode behavior (Geiger)
- Proximity combines RSSI and hit-rate in a 500 ms rolling window and maps to 0..100.
- RSSI is treated as dBm; if the SDK reports positive values, they are interpreted as negative magnitude (e.g., `60` -> `-60`).
- Output uses EMA smoothing (alpha ~0.2) and decays gradually after ~700 ms without target hits.

## Find Debug overlay (Geiger troubleshooting)
- Enable **Settings -> Find Debug Overlay** to show a collapsible debug card in Find.
- **Nearby mode** appears when no target EPC is set; the meter uses any tag RSSI.
- **Match status** helps explain “empty” sessions:
  - `No target`: Find is running without a target EPC.
  - `Not matched yet`: tags are seen, but none match the target EPC.
  - `Matched`: target EPC has been seen.
- **Any tag telemetry** (last EPC/RSSI + tags seen) proves inventory is active even if the target does not match.
- **Debug: disable filter** uses any tag to drive the Geiger meter for quick RF sanity checks.
- **Set target from last seen tag** updates the target to the most recent EPC when mismatch is confirmed.

## Repair & Verify (EPC mismatch tool)
- Use **Repair** only when a scanned tag EPC does not match the expected EPC for a selected item.
- A mismatch means the tag you scanned is not encoded with the selected item's EPC (wrong tag or incorrect encoding).
- Common failure causes: tag locked or not writable, wrong tag type, poor RF power/region, or weak coupling.
- Write success is confirmed only after a read-back verification matches the expected EPC.
