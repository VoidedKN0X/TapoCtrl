# Changelog

What changed in each TapoCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/TapoCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.1.0 (2026-11-12)

The first release of TapoCtrl: control your Tapo plugs and bulbs on your home network, without the Tapo app and without TP-Link's cloud.

### New

- **Devices** in a grid or list, each with its state and a big on/off button. Refreshes when you open the app and when you pull down.
- **Add by IP address, or scan your Wi-Fi network** with TP-Link's local discovery and a quick check of every address. Devices that move to a new address are found again by their MAC address.
- **Tapo account kept as hashes only:** your email and password are turned into the hashes the devices check and are never stored; the hashes are encrypted with a key in the phone's secure hardware.
- **Lights:** brightness, colour temperature, and hue and saturation for colour bulbs and light strips.
- **Energy** for plugs that measure it (such as the P110 and P115): power now, today and this month, with an estimated cost from your electricity price.
- **Rename** a device, and a **device info** page with model, firmware and hardware version, MAC address, Wi-Fi signal and IP address.
- **Firmware updates** done by the device itself: see the new version and its release notes, confirm, and follow the download, install and restart.
- **Never switches anything on its own:** starting the app, refreshing, scanning and adding devices only read; a device changes only when you tap its control or confirm a firmware update.
- **Privacy:** local network only (every other address is refused), no analytics, app lock, Hide in screenshots and Hide in recent apps.

### Not yet supported

- Devices with older firmware (securePassthrough), cameras, single sockets of power strips, hubs and sensors.
