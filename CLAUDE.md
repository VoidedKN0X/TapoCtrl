# TapoCtrl

Local-network controller for TP-Link Tapo plugs and bulbs in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings and the device list. Talks KLAP over plain HTTP (`HttpURLConnection`, no OkHttp) to devices on the LAN only.

## Layout

- `tapo/KlapCrypto.kt`: pure KLAP crypto (auth hashes v2/v1, handshake hashes, `KlapSession` key/iv/seq/sig, AES-128-CBC with the signature prefix). Unit-tested in `app/src/test` with vectors computed independently.
- `tapo/KlapClient.kt`: one client per IP. handshake1 → match the server hash against the saved account only (v2, v1; `chooseAuth`) → handshake2 → session; re-handshakes on expiry (cookie TIMEOUT), 401/403, error 9999, a decrypt failure or a broken connection (once per call). `LanHttp` posts with `Proxy.NO_PROXY`, no redirects, and refuses non-private addresses.
- `tapo/Discovery.kt`: UDP 20002 discovery (TDP header with CRC32, throwaway RSA public key made per scan) and the KLAP handshake1 probe over the phone's /24.
- `tapo/DeviceInfo.kt`: lenient parsing of get_device_info, get_energy_usage, get_current_power, get_latest_fw, get_fw_download_state.
- `tapo/EnergyHistory.kt`: the energy graphs (read-only): get_energy_data requests (hourly ≤ 8 days, daily from a quarter start, monthly from 1 January; local midnight sent as UTC seconds), parsing (values capped), assembling into 24/7/30/12 bars, and the get_energy_usage history lists (past24h/past7d/past30d/past1y) as fallback. `EnergyHistoryTest` uses recorded payloads; `NoAutomaticWritesTest` covers it.
- `data/AccountVault.kt`: only the KLAP hashes, AES-256-GCM with an AndroidKeyStore key (StrongBox when the phone has it, else the regular Keystore), in `noBackupFilesDir`.
- `data/Devices.kt`: the saved device list (JSON in DataStore, sealed at rest), live state per device, all device commands.
- `data/crypto`: encryption at rest (see below).
- `net/LanAddress.kt`: the private/link-local address check every connection goes through.
- `ui/devices`: Home (grid/list, pull to refresh), Add (IP and scan), Account, Device, Energy (Compose Canvas bar charts), Device info, Firmware.

## Hard rules

- **Local network only.** Connect only to private or link-local addresses (`LanAddress`); never to TP-Link's cloud or any other server. Numeric addresses only (no DNS). No proxies, no redirects.
- **Network permissions:** exactly `INTERNET` and `ACCESS_NETWORK_STATE` (CI checks). No location, Wi-Fi state or nearby-devices permission.
- **Never store the Tapo password or email.** Only the KLAP hashes, encrypted with the Keystore key.
- **Firmware:** only `get_latest_fw`, `fw_download` without parameters and `get_fw_download_state`. Never send a device a firmware URL, and never handle firmware files.
- **Never change a device on its own.** Start, resume, refresh, scan, add, re-login and re-handshake only read (`TapoMethods.READS` through `readOnly()`/`DeviceReads`). `set_device_info` only from a tap on that device's control, `fw_download` only after the confirmation dialog; writes are never retried after a broken connection. No schedules, automations, restore-last-state, widgets or tiles that switch things. `ReadOnlyPathsTest` and `NoAutomaticWritesTest` enforce this (no write call inside a Compose effect or `init` block, writes only from tap handlers on the device screens, no services, receivers, widgets, tiles or scheduled work).
- **Only the user's own account.** No built-in, blank, default or setup credentials, ever. No saved account: don't contact the device. No match: `WrongAccount`, stop, no other attempts and no handshake2 (wrong accounts must not count as login attempts). `AccountOnlyTest` enforces this.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`). Settings open only from the profile picture.

## Encryption at rest

- The device list (`devices_json` in the "devices" DataStore) is sealed with AES-256-GCM as text (`data/crypto/SealedText`: `TAPOCTRL-REST1:` + Base64 of the `SealedFile` bytes) under the data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `tapoctrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication). `account.bin` (`AccountVault`, its own Keystore key) and the app lock vault are separate and unchanged. Settings are not encrypted.
- Reading must accept both sealed and ordinary text (`SealedText.isSealed`). Without a data key the list is read and written as ordinary text, as before.
- Never overwrite a sealed list that can't be opened, never replace an existing `data.key`, and never create a Keystore key on the decrypt path (`KeystoreAes.existing`). A sealed list that can't be opened puts `DeviceRepository.unreadable` on: `save` writes nothing and Home shows "Can't open your device list".
- Keystore work happens once, on `Dispatchers.IO`, in `DeviceRepository`'s start-up coroutine (and in `retry`/`startEmpty`); everything else uses the key from memory. `save` and `refreshAll` wait for the first read (`saved()`).
- Storage only: nothing here touches `tapo/` or what is sent to a device. `SealedFileTest` covers the format and the key rules (`./gradlew :app:testDebugUnitTest`; CI doesn't run unit tests).

`settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md` and `README.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "TapoCtrl X.Y.Z". Push straight to `main`.
- Every new version needs a detailed, user-facing entry at the top of `CHANGELOG.md`; CI refuses to release without one.
