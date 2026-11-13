# Changelog

What changed in each TapoCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/TapoCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
