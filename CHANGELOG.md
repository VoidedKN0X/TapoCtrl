# Changelog

What changed in each TapoCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/TapoCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

Changes on `main` that aren't in a release yet. They ship with the next version. Built and checked by CI only; not run on a phone.

### Changed

- **Newer build tools; nothing changes in how TapoCtrl looks or works.** TapoCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.13.2 → 9.5.1, Gradle 8.14.3 → 9.9.2 and Compose BOM 2026.06.01 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). Kotlin stays at 2.4.21, and every library stays at the version it had (TapoCtrl was already on the versions the newest Ctrl apps use). No library was added, and the app is still Google-free (CI checks it).
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions TapoCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4. TapoCtrl's own keep rules are unchanged. The newer R8 has stricter defaults than the old one, so the release build needs a check on a phone before the next version is published.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

### Security

- **The signing key is no longer available to the build.** Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Releases are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. The app itself is unchanged by this. The release step of the new workflow has not run yet for TapoCtrl: it runs for the first time with the next version.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache, and the Gradle plugin portal is no longer a plugin source.** Other Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause (found on DateCtrl): with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the code scan start from a clean Gradle home (a build takes a minute or two longer), and `settings.gradle.kts` lists only Google's Maven and Maven Central for plugins. Dependency verification itself is unchanged and as strict as before. Nothing in the app changed.

## v0.3.0 (2026-11-20)

**This release encrypts some of what the app stores on the phone. An older version cannot read it afterwards, so export or back up anything you need before you update (details below).** Built and checked by CI only: nothing in this release had been run on a phone when it was published. It has also never been tried with real Tapo devices.

### Security

- **Your device list is now encrypted on the phone.** The list of devices you added (each one's address on your network, name, model, type and MAC address) was ordinary text in TapoCtrl's private settings storage, protected by Android's own storage encryption and app sandbox only. It is now also encrypted with AES-256-GCM. The key is 32 random bytes kept in memory while the app runs and stored in `data.key`, itself encrypted by an AES-256-GCM key in the Android Keystore (the phone's secure hardware, StrongBox when the phone has it) that can't be copied off the phone. It needs no PIN or fingerprint and works the same with the app lock on or off. This protects the list when the app's files are copied off the phone; it does not protect against something that can already read the running app's memory.
- **Checked and left as they are:** your Tapo account hashes were already encrypted with their own Keystore key (`account.bin`); that file, its key and its format are untouched. The app lock's PIN data was already protected by the secure hardware. The settings (theme, grid or list, electricity price, lock options) stay ordinary: they say nothing about your devices. Live device state and energy history are never stored; they are read from the device each time.
- **How the existing list is moved over:** at the first start the list is read as before. The same text is then encrypted, opened again and compared, and only if that matches does it replace the ordinary text, in one step of the settings storage (which writes a complete new file, flushes it and renames it over the old one). If anything fails, or the phone has no working Keystore, the ordinary text stays in use exactly as it was and TapoCtrl tries again at the next start. Killed halfway, the list is either still the ordinary text or already the encrypted copy. Both kinds stay readable: encrypted text starts with the marker `TAPOCTRL-REST1:`.
- **If the key is ever gone** (it lives in this phone's secure hardware, so that could only happen if Android's Keystore loses it), TapoCtrl does not show an empty list and does not overwrite anything. Home shows "Can't open your device list" with **Try again**; nothing is saved while it shows, and the add button is hidden. **Start empty** (after a confirmation) keeps the unreadable list aside in the same storage under another name, and `data.key` as `data.key.unreadable`, instead of deleting them; you can then add your devices again (a scan finds them). "Forgot PIN?" on the lock screen still deletes the list, also one that can't be opened.
- **Going back to an older build:** TapoCtrl 0.2.0 and older can't read the encrypted list. They show no devices, and the first device you add there replaces the encrypted list for good. TapoCtrl has no export, so after going back you add your devices again; your account and the devices themselves are not affected.

### Changed

- **No "No devices yet" flash at start, and the first refresh waits for the list.** Home stays blank for the moment it takes to read the saved list (now including asking the secure hardware for the key) instead of briefly showing the empty screen, and the refresh that runs when the app opens waits until the list is read instead of possibly finding nothing to refresh. Reading only, as before: nothing is ever sent to a device that changes it.

## v0.2.0 (2026-11-13)

### New

- **Energy graphs** for plugs that measure energy (such as the P110 and P115). On a plug's page, tap **Energy use** to see today hour by hour, the last 7 days and the last 30 days day by day, and the last 12 months month by month. Each graph shows the total, the highest hour, day or month, and an estimated cost if you've set your electricity price. Tap a bar to see its value.
- The history comes from the plug's own memory, so it covers time when TapoCtrl wasn't open. Plugs with older firmware that keep their history differently are supported too.
- **Still read-only:** looking at the graphs only reads from the plug. TapoCtrl still never switches a plug or bulb on or off by itself, and the automatic tests that check this now cover the energy graphs too.

## v0.1.1 (2026-11-13)

A security and maintenance release. Nothing changes in how you use TapoCtrl, and TapoCtrl still never switches a plug or bulb on or off by itself.

### Security

- **More checks that TapoCtrl never changes a device on its own:** new automatic tests make sure power, brightness, colour, rename and firmware commands can only come from a tap, never from opening, resuming or refreshing a screen, and that TapoCtrl has nothing that runs in the background (no services, widgets, tiles or scheduled jobs).
- **A broken reply from a device** (for example after a glitch on the network) now shows as "can't reach the device" instead of possibly closing the app.
- **Your Tapo account key:** on phones with a separate security chip (StrongBox), a newly saved account is now protected by that chip. An account you already saved keeps working as it is.
- The build tool download is now checked against its published checksum.

### Performance

- Updated Jetpack Compose, AndroidX and Kotlin libraries to their current versions.
- Release builds drop debug logging.
## v0.1.0 (2026-11-12)

The first release of TapoCtrl: control your Tapo plugs and bulbs on your home network, without the Tapo app and without TP-Link's cloud.

### New

- **Devices** in a grid or list, each with its state and a big on/off button. Refreshes when you open the app and when you pull down.
- **Add by IP address, or scan your Wi-Fi network** with TP-Link's local discovery and a quick check of every address. Devices that move to a new address are found again by their MAC address.
- **Tapo account kept as hashes only:** your email and password are turned into the hashes the devices check and are never stored; the hashes are encrypted with a key in the phone's secure hardware. TapoCtrl only ever uses your own account: no built-in or setup accounts, and if a device doesn't accept yours it says so and stops.
- **Lights:** brightness, colour temperature, and hue and saturation for colour bulbs and light strips.
- **Energy** for plugs that measure it (such as the P110 and P115): power now, today and this month, with an estimated cost from your electricity price.
- **Rename** a device, and a **device info** page with model, firmware and hardware version, MAC address, Wi-Fi signal and IP address.
- **Firmware updates** done by the device itself: see the new version and its release notes, confirm, and follow the download, install and restart.
- **Never switches anything on its own:** starting the app, refreshing, scanning and adding devices only read; a device changes only when you tap its control or confirm a firmware update.
- **Privacy:** local network only (every other address is refused), no analytics, app lock, Hide in screenshots and Hide in recent apps.

### Not yet supported

- Devices with older firmware (securePassthrough), cameras, single sockets of power strips, hubs and sensors.
