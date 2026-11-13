# TapoCtrl

**Control your TP-Link Tapo plugs and bulbs on your home network, without the Tapo app and without TP-Link's cloud.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/TapoCtrl?label=download)](https://github.com/VoidedKN0X/TapoCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Local network only](https://img.shields.io/badge/network-local%20only-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

TapoCtrl talks to your Tapo devices directly over your Wi-Fi, with the same encrypted local protocol (KLAP) the official app uses on your network. Your phone never contacts TP-Link: no account login online, no cloud, no tracking. Switch things on and off, dim and colour your bulbs, see what your plugs use, and update their firmware.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/TapoCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## Features

- **Your devices at a glance:** a grid or list of every plug and bulb with its state and a big on/off button. It refreshes when you open the app and when you pull down
- **Add by address or scan:** type a device's IP address, or let TapoCtrl scan your Wi-Fi subnet (TP-Link's local discovery broadcast plus a quick check of every address). Devices that change address are found again by their MAC address
- **Lights:** brightness, colour temperature, and hue and saturation on colour bulbs and light strips
- **Energy** on plugs that measure it (such as the P110 and P115): power right now, today's and this month's energy, and an estimated cost from the electricity price you set
- **Rename** a device (the name is saved on the device, so it shows in the Tapo app too)
- **Device info:** model, firmware and hardware version, MAC address, Wi-Fi signal and IP address
- **Firmware updates:** TapoCtrl asks the device whether TP-Link has a newer version, shows the version and release notes, and on your OK tells the device to install it. You can follow the download, install and restart. The device reboots at the end, so a plug briefly cuts power to whatever is plugged into it
- **Never switches anything on its own:** opening the app, refreshing, scanning and adding devices only read their state. A device changes only when you tap its own control (power button, slider, rename) or confirm a firmware update. No schedules, automations or "restore last state"
- **Your account stays on the phone:** you enter your Tapo email and password once; TapoCtrl keeps only the two hashes the devices check, encrypted with a key in the phone's secure hardware. The password is never stored
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on)
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, high contrast, one violet accent, Space Grotesk and JetBrains Mono

## What works, and what doesn't yet

- **Devices with current firmware** (the KLAP protocol): Tapo plugs (P100, P105, P110, P115 and similar), bulbs (L510, L530, L630 and similar) and light strips (L900, L920, L930). Kasa devices that use KLAP may work for on/off.
- **Older firmware** that still uses the earlier "securePassthrough" protocol isn't supported yet. TapoCtrl tells you when a device uses it; updating the device's firmware with the Tapo app once usually moves it to KLAP.
- **Cameras** (C100, C200 and others) aren't supported in this version: no live view (RTSP) and no controls. The scan lists them but can't add them.
- **Power strips** (P300 and similar): only the strip as a whole; its single sockets aren't shown yet.
- **Hubs, sensors, switches, schedules, timers and scenes** aren't supported yet.
- **Setting up a new device** (joining it to your Wi-Fi) still needs the Tapo app once. After that, TapoCtrl can do the rest.
- **Remote control from outside your home** isn't possible: TapoCtrl only works on the same network as your devices. That's the point.

**Tested on:** no Tapo device has been controlled end to end yet. The protocol code is checked with unit tests against independently computed values, and during development a real P110 was found by the scan (both ways) and answered the first step of the handshake; it hasn't been switched or read with a real account yet. Please [report](https://github.com/VoidedKN0X/TapoCtrl/issues/new/choose) which devices work for you.

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- Your phone on the same Wi-Fi network as your Tapo devices
- The Tapo account your devices were set up with (its email and password)
- Works on any Android ROM as a normal app: stock Android, LineageOS, GrapheneOS, CalyxOS, /e/OS and the phone makers' own systems. It doesn't need root.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google.

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/TapoCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `TapoCtrl-v0.1.0.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings and devices are kept.

**Automatic updates:** on your phone, tap the Obtainium button above. Install [Obtainium](https://github.com/ImranR98/Obtainium) first; the button adds TapoCtrl and installs every new release for you. If the button doesn't open the app, add `https://github.com/VoidedKN0X/TapoCtrl` in Obtainium by hand.

## Getting started

1. **Tapo account:** tap the card at the top and enter the email and password of your Tapo account. They're turned into hashes on the phone right away.
2. **Add devices:** tap **+**, then **Scan network**, and **Add** next to each device. Or type a device's IP address (your router lists it; giving the device a fixed address there keeps it the same).
3. **Use them:** the power button on each tile switches it. Tap a tile for brightness, colour, energy, rename, device info and firmware updates.
4. **Settings** (profile picture): your Tapo account, grid or list, electricity price and currency, and privacy.

## Is it safe to install?

- **Open source.** All of TapoCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks the app's network permissions (only the two it needs) and that it has no Google libraries.
- **Always signed with the same key.** Android refuses to install an update signed with a different key over TapoCtrl. The SHA-256 fingerprint of TapoCtrl's signing certificate is:

  ```
  BD:56:98:B5:ED:E9:0C:99:94:60:BF:0A:A2:C7:9A:6E:63:08:B5:E7:65:C7:BC:06:56:C4:90:A7:05:BF:EB:E8
  ```

  Advanced users can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).

**Check a download.** Every release is built and published by GitHub Actions, never on a personal computer:

- The release notes end with the APK's **SHA-256** checksum. Compare it with `sha256sum TapoCtrl-vX.Y.Z.apk` (or `Get-FileHash` on Windows).
- Each release APK has a signed **build provenance attestation**. With the [GitHub CLI](https://cli.github.com/), `gh attestation verify TapoCtrl-vX.Y.Z.apk -R VoidedKN0X/TapoCtrl` proves it was built by this repo's workflow from the tagged commit.
- The signing-certificate fingerprint above stays the same for every release.

**Permissions.**

| Permission | What TapoCtrl uses it for |
| --- | --- |
| Internet (`INTERNET`) | Android's permission for any network connection, including local ones. TapoCtrl uses it only to reach your devices on your home network; it refuses every address that isn't a private or link-local one (see [Privacy](#privacy)). |
| View network connections (`ACCESS_NETWORK_STATE`) | Finding this phone's Wi-Fi address and subnet for **Scan network**. |
| Use biometric hardware | Optional fingerprint unlock for the app lock. |
| Hide other apps' floating windows over the PIN screen | While the lock, PIN, account or a confirmation screen is showing, other apps' floating windows are hidden over it. |
| App-internal broadcasts (`com.vknxworks.tapoctrl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`) | Added by AndroidX so that only TapoCtrl itself can send to its own internal receivers. |

No location, no Wi-Fi state, no nearby devices permission.

## App lock

Turn the app lock on under **Settings > Privacy and security > App lock**. It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours.
- **The PIN is never stored.** It's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to reset TapoCtrl: it forgets your account hashes and your device list, resets its settings and turns the lock off. Your devices themselves aren't changed.

## Privacy

**Telemetry-free and cloud-free.** TapoCtrl has no analytics, crash reporting, usage stats or tracking of any kind, and **never contacts TP-Link or any other server**.

- **Local network only.** Every connection goes to an address you added or the scan found, and TapoCtrl checks before connecting that it's a private or link-local address (10.x, 172.16–31.x, 192.168.x, 169.254.x, or IPv6 fc00::/7 and fe80::/10). Host names are never looked up. Proxies and redirects are never followed.
- **Plain HTTP, encrypted content.** Tapo devices only speak HTTP on port 80, so TapoCtrl allows unencrypted HTTP connections (Android's network security config can't limit that to address ranges; the check above does). What's sent is encrypted with KLAP: AES-128 with a key both sides derive from your account during each session's handshake.
- **Your Tapo account:** the email and password are turned into the two hashes devices check (SHA-256 of SHA-1 hashes for current firmware, MD5 for some older KLAP firmware) and immediately forgotten. Only the hashes are kept, encrypted with AES-256-GCM using a key in the Android Keystore (in StrongBox, the phone's separate security chip, when it has one). They're sent nowhere: devices prove they know the same account, and TapoCtrl proves it back, without the hash itself crossing the network.
- **Only your own account.** TapoCtrl has no built-in, blank or setup accounts. If a device doesn't accept your account, TapoCtrl says so and stops; it tries nothing else. Without a saved account it doesn't contact your devices at all (only **Scan network** looks for them, without any account data).
- **Scan network** sends TP-Link's discovery broadcast (with a throwaway key made on the phone for that scan) and a random 16-byte handshake start to each address of your Wi-Fi's /24 subnet. Nothing about you or your account is sent.
- **Firmware updates** are fetched by the device itself from TP-Link, as with the Tapo app. TapoCtrl only asks the device to check and to install; it never sends the device a download address and never touches the firmware.
- Your devices, settings and account hashes are in TapoCtrl's private storage. Android backups don't include them, and TapoCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## How TapoCtrl is made

TapoCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

TapoCtrl isn't made by or affiliated with TP-Link. Tapo is a trademark of TP-Link. The KLAP protocol was documented by the open-source community (projects such as python-kasa and plugp100); TapoCtrl is its own implementation.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 or newer and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/TapoCtrl.git
   cd TapoCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`. `./gradlew testDebugUnitTest` runs the protocol tests.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

**Dependency verification.** Every library and build plugin download is checked against the sha256 checksums in `gradle/verification-metadata.xml`, and the build fails on any mismatch. When you change a dependency, a plugin or the Gradle version, run the **Dependency verification metadata** workflow (Actions tab, *Run workflow*), check the diff of the file it prints and commit it in the same push.

## Support the project

TapoCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). Please report bugs and ideas with the [bug report and feature request forms](https://github.com/VoidedKN0X/TapoCtrl/issues/new/choose). Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| [CallCtrl](https://github.com/VoidedKN0X/CallCtrl) | Phone app: dialer, call history and in-call screen |
| [CameraCtrl](https://github.com/VoidedKN0X/CameraCtrl) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [GalleryCtrl](https://github.com/VoidedKN0X/GalleryCtrl) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| [MailCtrl](https://github.com/VoidedKN0X/MailCtrl) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| [RecorderCtrl](https://github.com/VoidedKN0X/RecorderCtrl) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [ShutterCtrl](https://github.com/VoidedKN0X/ShutterCtrl) | Remote control and photo transfer for Canon cameras |
| [SoundCtrl](https://github.com/VoidedKN0X/SoundCtrl) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| **TapoCtrl** (this app) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

TapoCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).
