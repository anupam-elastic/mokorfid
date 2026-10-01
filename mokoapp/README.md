# Moko RFID (C72) — v1 Single-tag detect

Minimal Android app that integrates Chainway **DeviceAPI** and reads a single UHF RFID tag on a **Chainway C72** handheld.

## What it does

1. Opens UHF module (`RFIDWithUHFUART.init`)
2. On **Scan RFID** or pistol trigger → short inventory (~1.5s)
3. Picks the **strongest RSSI** tag
4. Shows **EPC** + **RSSI** on screen

No barcode, backend, or mapping save yet.

## Requirements

- Android Studio Hedgehog+ (or any IDE with AGP 8.2)
- JDK 17
- Physical **Chainway C72** + at least one UHF tag  
  (Emulator cannot exercise UHF)

## Open & run

1. Open folder `mokoapp/` in Android Studio (**Open**, not New Project)
2. Let Gradle sync (downloads dependencies)
3. Connect C72 via USB with debugging enabled
4. Run ▸ `app` on the device

Or build APK:

```bash
cd mokoapp
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

If `./gradlew` is missing, use Android Studio’s first sync (it can generate the wrapper), or:

```bash
gradle wrapper --gradle-version 8.2
```

## Project layout

```
mokoapp/
  app/libs/DeviceAPI_ver20230301_release.aar   # Chainway SDK
  app/src/main/java/com/mokobara/mokoapp/
    MainActivity.kt
    uhf/UhfReaderHelper.kt
```

## On-device test checklist

1. Install app on C72
2. Open **Moko RFID** → status chip should become **UHF ON**
3. Hold one tag near the antenna
4. Tap **Scan RFID** (or pull trigger)
5. EPC appears (e.g. `E280…`) with RSSI

If status stays **UHF OFF**: wrong device / missing UHF module / another app holding the UART — reboot C72 and retry.

## Trigger keys

Soft button always works. Hardware keycodes handled: `139`, `280`, `293`, `F9`, `L1`, `R1` (OEM maps vary).

## Next steps (not in v1)

- EAN barcode → product card
- Save `USN ↔ RFID ↔ SKU`
- Conflict check if tag already mapped
