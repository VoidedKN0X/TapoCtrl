# TapoCtrl

Local-network controller for TP-Link Tapo plugs and bulbs in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings and the device list. Talks KLAP over plain HTTP (`HttpURLConnection`, no OkHttp) to devices on the LAN only.

## Layout

- `tapo/KlapCrypto.kt`: pure KLAP crypto (auth hashes v2/v1, handshake hashes, `KlapSession` key/iv/seq/sig, AES-128-CBC with the signature prefix). Unit-tested in `app/src/test` with vectors computed independently.
- `tapo/KlapClient.kt`: one client per IP. handshake1 → match the server hash against the saved account only (v2, v1; `chooseAuth`) → handshake2 → session; re-handshakes on expiry (cookie TIMEOUT), 401/403, error 9999, a decrypt failure or a broken connection (once per call). `LanHttp` posts with `Proxy.NO_PROXY`, no redirects, and refuses non-private addresses.
- `tapo/Discovery.kt`: UDP 20002 discovery (TDP header with CRC32, throwaway RSA public key made per scan) and the KLAP handshake1 probe over the phone's /24.
- `tapo/DeviceInfo.kt`: lenient parsing of get_device_info, get_energy_usage, get_current_power, get_latest_fw, get_fw_download_state.
- `data/AccountVault.kt`: only the KLAP hashes, AES-256-GCM with an AndroidKeyStore key, in `noBackupFilesDir`.
- `data/Devices.kt`: the saved device list (JSON in DataStore), live state per device, all device commands.
- `net/LanAddress.kt`: the private/link-local address check every connection goes through.
- `ui/devices`: Home (grid/list, pull to refresh), Add (IP and scan), Account, Device, Device info, Firmware.

## Hard rules

- **Local network only.** Connect only to private or link-local addresses (`LanAddress`); never to TP-Link's cloud or any other server. Numeric addresses only (no DNS). No proxies, no redirects.
- **Network permissions:** exactly `INTERNET` and `ACCESS_NETWORK_STATE` (CI checks). No location, Wi-Fi state or nearby-devices permission.
- **Never store the Tapo password or email.** Only the KLAP hashes, encrypted with the Keystore key.
- **Firmware:** only `get_latest_fw`, `fw_download` without parameters and `get_fw_download_state`. Never send a device a firmware URL, and never handle firmware files.
- **Never change a device on its own.** Start, resume, refresh, scan, add, re-login and re-handshake only read (`TapoMethods.READS` through `readOnly()`/`DeviceReads`). `set_device_info` only from a tap on that device's control, `fw_download` only after the confirmation dialog; writes are never retried after a broken connection. No schedules, automations, restore-last-state, widgets or tiles that switch things. `ReadOnlyPathsTest` enforces this.
- **Only the user's own account.** No built-in, blank, default or setup credentials, ever. No saved account: don't contact the device. No match: `WrongAccount`, stop, no other attempts and no handshake2 (wrong accounts must not count as login attempts). `AccountOnlyTest` enforces this.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`). Settings open only from the profile picture.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "TapoCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
