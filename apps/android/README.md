# Open Health Android: prototype

An experimental, local-first Android client for Oura rings built on the shared
Rust core. It is unaffiliated with Oura and is not a medical device. Product scope
and status live in the PRD (`ANDROID-PRD.md`, kept outside this repository).

What it does today:

- **Scan** for rings and select one.
- **Read ring info**: serial, hardware id, and firmware, with no key and no changes
  to the ring. Works on a ring still paired with the official app (force-stop it).
- **Pair this ring**: only for a factory-reset ring. The pairing key is saved,
  encrypted with the Android Keystore, *before* it is sent to the ring, and becomes
  active only after the ring authenticates with it. A retry after an interrupted
  pairing reuses the same key.
- **Check pairing**, **Turn on measurements** (daytime heart rate and SpO2), and
  **Sync now** into a local SQLite database, followed by a summary card.

Validated on hardware so far: scanning, bonding, and the read-only connection
(Galaxy S23 with a Ring 4). Pairing, measurement setup, and sync are implemented
and unit-tested but not yet confirmed on a ring.

Before pairing a reset ring, forget it in Android Bluetooth settings, and do not
connect anything else to it first. The key exists only on this phone: uninstalling
the app or clearing its data means resetting the ring again (key backup is planned).

## Windows prerequisites

Validated with JDK 17, Android SDK Platform 35, Build Tools 35, NDK 27.2.12479018,
Visual Studio 2022 C++ Build Tools, and Rust stable.

- Rust and the `aarch64-linux-android` target.
- Internet access for the checked-in Gradle 8.9 wrapper and dependencies.
- A Bluetooth LE-capable Android 12+ phone for discovery; the emulator does not
  validate ring Bluetooth.

Run `build-core.ps1` from a Visual Studio x64 developer prompt, because Cargo also
builds host-side tools. Set `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_NDK_HOME`:

```powershell
rustup target add aarch64-linux-android
Set-Location apps\android
.\build-core.ps1
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

`build-core.ps1` builds `liboura_core.so` and generates Kotlin bindings under
`app\build\generated`. Re-run it after changing the Rust core. It keeps symbol
tables because UniFFI reads binding metadata from them.

Install `app\build\outputs\apk\debug\app-debug.apk` with `adb install -r` or
Android Studio. Bluetooth scanning needs Nearby devices permission. Scan results
remain on the device and are not persisted. No Internet permission is requested.

Android app data is excluded from automatic cloud backup and device transfer.

The Bluetooth link (`GattLink.kt`) scans for the selected ring right before
connecting and connects with that scan's device object (a remembered address string
caused connection timeouts). It requests a 247-byte MTU, bonds if the phone has no
bond with the ring, subscribes to the ring's notify characteristics one at a time,
serializes write-with-response requests, forwards every notification to
`RingSession.push_frame`, and cancels the Rust session on any disconnect or GATT
failure. Connection setup is retried up to 3 times on transient Android errors
(133, 147, 62, 8). Each step is logged under the `OpenHealthBle` logcat tag without
key material.

Not yet implemented: key backup and import, data export, history screens,
background sync, and live heart rate.
