# Moko RFID (C72) — Map testing

Android app for Chainway **C72**: home → Single mapping (USN barcode + RFID) or Bulk (coming soon).

## What it does

1. **Home** — Single mapping / Bulk mapping
2. **Single mapping** (auto flow)
   - Enter screen → barcode scan starts
   - USN captured → RFID scan starts automatically
   - Both filled → **Save** appears (demo toast only)
3. **Bulk mapping** — Coming soon

## Requirements

- Android Studio Hedgehog+ (or any IDE with AGP 8.2)
- JDK 17
- Physical **Chainway C72** + UHF tag + barcode/USN label  
  (Emulator cannot exercise barcode/UHF)

## Open & run

1. Open folder `mokoapp/` in Android Studio (**Open**, not New Project)
2. Let Gradle sync
3. Connect C72 via USB with debugging enabled
4. Run ▸ `app` on the device

Or build APK:

```bash
cd mokoapp
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
mokoapp/
  app/libs/DeviceAPI_ver20230301_release.aar
  app/src/main/java/com/mokobara/mokoapp/
    MainActivity.kt              # Home
    SingleMapActivity.kt         # USN + RFID
    BulkMapActivity.kt           # Coming soon
    barcode/BarcodeHelper.kt
    uhf/UhfReaderHelper.kt
```

## On-device test checklist

1. Install on C72 → **Map testing** home
2. Bulk → Coming soon → Back
3. Single mapping → wait for **Ready**
4. Scan USN → field fills with barcode value
5. Scan RFID → field fills with EPC
6. Save → “Save (demo only)” toast

If status stays **Offline**: wrong device / module busy — reboot C72 and retry.

## Trigger keys

Hardware trigger follows the last selected scan target (USN or RFID). Soft buttons always work. Keycodes: `139`, `280`, `293`, `F9`, `L1`, `R1`.

## Next steps

- Persist `USN ↔ RFID` (+ SKU)
- Conflict check if already mapped
- Real bulk pairing UI
