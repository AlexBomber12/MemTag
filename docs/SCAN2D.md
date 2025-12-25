# Scan2D (Chainway C72)

## Overview
- The app uses Chainway BarcodeUtility broadcast mode for QR scanning.
- Broadcast scan means the scanner sends an Intent broadcast containing the decoded string.
- QR content must equal the EPC hex string (no prefixes or spaces).
- QR scanning is a fallback path; UHF remains the primary workflow.
- Broadcast action and extra key are configurable in Settings > 2D Scan.

## Device setup (KeyboardEmulator / BarcodeUtility)
1. Open the Chainway KeyboardEmulator (or BarcodeUtility) app.
2. Enable the Barcode2D / 2D module.
3. Set Output Mode to Broadcast.
4. Set Broadcast Action and Extra to match the app settings.
5. Disable prefix/suffix and Enter/TAB if the device UI exposes these options.

## App defaults
- Broadcast action: com.alexbomber12.memtag.SCAN
- Broadcast extra key: data

## Quick verification
1. Open Lookup and tap Scan QR.
2. Scan a QR code containing the EPC hex.
3. The EPC should populate and lookup should run immediately.
4. If nothing happens, re-check the device output mode and action/extra values.
