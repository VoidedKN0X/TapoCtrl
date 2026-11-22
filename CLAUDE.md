# TapoCtrl

Local-network controller for TP-Link Tapo plugs, bulbs and cameras in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings and the device list. Talks KLAP (or securePassthrough on older firmware) over plain HTTP, and HTTPS with a pinned self-signed certificate to cameras (`HttpURLConnection`, no OkHttp), to devices on the LAN only.

## Layout

- `tapo/KlapCrypto.kt`: pure KLAP crypto (auth hashes v2/v1, handshake hashes, `KlapSession` key/iv/seq/sig, AES-128-CBC with the signature prefix). Unit-tested in `app/src/test` with vectors computed independently.
- `tapo/KlapClient.kt`: one client per IP. handshake1 → match the server hash against the saved account only (v2, v1; `chooseAuth`) → handshake2 → session; re-handshakes on expiry (cookie TIMEOUT), 401/403, error 9999, a decrypt failure or a broken connection (once per call). `LanHttp` posts with `Proxy.NO_PROXY`, no redirects, and refuses non-private addresses.
- `tapo/PassthroughCrypto.kt`, `tapo/PassthroughClient.kt`: securePassthrough for older plug/bulb firmware (RSA-1024 handshake, login_device v1/v2, AES-CBC), as python-kasa `AesTransport` / plugp100. `SmartClient` (same file) tries KLAP first and switches to securePassthrough when handshake1 says the device has no KLAP (nothing sent yet), but only for a device never seen speaking KLAP: `SavedDevice.protocol` ("klap"/"passthrough"/empty, learnt on refresh, `SavedProtocol.mayRecord`) and the client's own `klapSeen` pin it, and a pinned device gets `TapoException.ProtocolChanged` instead (`SavedProtocol.mayFallBack`, `KlapPinningTest`). Never add another path to securePassthrough. A scan only moves a plug/bulb to a new address after a KLAP read there returns the same MAC (`updateAddresses`).
- `tapo/CameraCrypto.kt`, `tapo/Spake2p.kt`, `tapo/CameraClient.kt`, `tapo/Camera.kt`: cameras over HTTPS 443. Login picked by the client: `login`/`discover` → TPAP (SPAKE2+ P-256, AES-128-CCM; pytapo `transport/tpap`, tapo crate) or encrypt_type 3 (python-kasa `SslAesTransport`; device_confirm checked before digest_passwd) or the older hashed MD5 login. User always "admin" with the account password's MD5/SHA-256. Every request is one method in a `multipleRequest`. `CameraCryptoTest` has the RFC 9383 vector and known answers from the tapo crate, pytapo and python-kasa; `CameraC200Test` the C200 discovery/pake_register fixtures. Every login step that stops says which step and what the camera asked (`TapoException.Unsupported`, `DeviceError.step`; names only, never salts or keys).
- `net/PinnedTls.kt`: HTTPS trusting only the SHA-256 fingerprint the user confirmed when adding the camera (`SavedDevice.certSha256`); `fetchLeaf` reads the certificate without sending anything.
- `tapo/Discovery.kt`: UDP 20002 discovery (TDP header with CRC32, throwaway RSA public key made per scan; broadcast on the Wi-Fi /24 plus unicast to every target) and the KLAP handshake1 probe, at most 48 at a time, with progress and cancellation. Android-free (unit-tested on the JVM). `tapo/ScanRange.kt`: the typed range for "Scan a network range" (CIDR, at most /22, inside 10/8, 172.16/12, 192.168/16 or 100.64/10). `net/LocalNetwork.kt`: the phone's Wi-Fi/Ethernet address from all networks, skipping VPNs (Tailscale makes the VPN the active network); no Wi-Fi name, no location.
- `tapo/DeviceInfo.kt`: lenient parsing of get_device_info, get_energy_usage, get_current_power, get_latest_fw, get_fw_download_state.
- `tapo/EnergyHistory.kt`: the energy graphs (read-only): get_energy_data requests (hourly ≤ 8 days, daily from a quarter start, monthly from 1 January; local midnight sent as UTC seconds), parsing (values capped), assembling into 24/7/30/12 bars, and the get_energy_usage history lists (past24h/past7d/past30d/past1y) as fallback. `EnergyHistoryTest` uses recorded payloads; `NoAutomaticWritesTest` covers it.
- `data/AccountVault.kt`: the KLAP hashes, MD5/SHA-256 of the password (cameras), SHA-1 of email and password (securePassthrough v2) and, only when the user turns on "Keep the password for older firmware", the password (securePassthrough v1 sends it). Format version 2; version 1 files (KLAP hashes only) are still read and reported as `complete = false`. AES-256-GCM with an AndroidKeyStore key (StrongBox when the phone has it, else the regular Keystore), in `noBackupFilesDir`.
- `data/Devices.kt`: the saved device list (JSON in DataStore, sealed at rest), live state per device, all device commands.
- `data/crypto`: encryption at rest (see below).
- `net/LanAddress.kt`: the private/link-local address check every connection goes through (since 0.7 also 100.64/10, the shared address space Tailscale uses, so networks reached through a VPN work).
- `ui/devices`: Home (grid/list, pull to refresh), Add (IP and scan), Account, Device, Energy (Compose Canvas bar charts), Device info, Firmware.

## Hard rules

- **Local network only.** Connect only to private, link-local or 100.64/10 addresses (`LanAddress`); a network reached through the user's own VPN route (Tailscale) counts. No automatic switching between addresses or networks, and never read the Wi-Fi name; never to TP-Link's cloud or any other server. Numeric addresses only (no DNS). No proxies, no redirects.
- **Network permissions:** exactly `INTERNET` and `ACCESS_NETWORK_STATE` (CI checks). No location, Wi-Fi state or nearby-devices permission.
- **Never store the Tapo email, and the password only on the user's explicit opt-in.** Only values derived from the account (see `AccountVault`), encrypted with the Keystore key; the password itself only with "Keep the password for older firmware" (owner-approved, 0.6), said plainly in the app, README and CHANGELOG.
- **Cameras:** only the certificate whose fingerprint the user confirmed; a settings import never brings a fingerprint (imported cameras must be added again). No live video.
- **Firmware:** only `get_latest_fw`, `fw_download` without parameters and `get_fw_download_state`. Never send a device a firmware URL, and never handle firmware files.
- **Never change a device on its own.** Start, resume, refresh, scan, add, re-login and re-handshake only read (`TapoMethods.READS` through `readOnly()`/`DeviceReads`). `set_device_info` and the camera writes (`setLensMaskConfig`, `motorMove`, `motorMoveToPreset`, `rebootDevice` after a confirmation) only from a tap on that device's control, `fw_download` only after the confirmation dialog; writes are never retried after a broken connection. No schedules, automations, restore-last-state, widgets or tiles that switch things. `ReadOnlyPathsTest` and `NoAutomaticWritesTest` enforce this (no write call inside a Compose effect or `init` block, writes only from tap handlers on the device screens, no services, receivers, widgets, tiles or scheduled work).
- **Only the user's own account.** No built-in, blank, default or setup credentials, ever. No saved account: don't contact the device. No match: `WrongAccount`, stop, no other attempts and no handshake2 (wrong accounts must not count as login attempts). `AccountOnlyTest` enforces this.
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`). Settings open only from the profile picture.

## Encryption at rest

- The device list (`devices_json` in the "devices" DataStore) is sealed with AES-256-GCM as text (`data/crypto/SealedText`: `TAPOCTRL-REST1:` + Base64 of the `SealedFile` bytes) under the data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `tapoctrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication). `account.bin` (`AccountVault`, its own Keystore key) and the app lock vault are separate and unchanged. Settings are not encrypted.
- Reading must accept both sealed and ordinary text (`SealedText.isSealed`). Without a data key the list is read and written as ordinary text, as before.
- Never overwrite a sealed list that can't be opened, never replace an existing `data.key`, and never create a Keystore key on the decrypt path (`KeystoreAes.existing`). A sealed list that can't be opened puts `DeviceRepository.unreadable` on: `save` writes nothing and Home shows "Can't open your device list".
- Keystore work happens once, on `Dispatchers.IO`, in `DeviceRepository`'s start-up coroutine (and in `retry`/`startEmpty`); everything else uses the key from memory. `save` and `refreshAll` wait for the first read (`saved()`).
- Storage only: nothing here touches `tapo/` or what is sent to a device. `SealedFileTest` covers the format and the key rules (`./gradlew :app:testDebugUnitTest`; CI runs the unit tests on every build).

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
