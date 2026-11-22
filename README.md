# TapoCtrl

**Control your TP-Link Tapo plugs, bulbs and cameras on your home network, without the Tapo app and without TP-Link's cloud.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/TapoCtrl?label=download)](https://github.com/VoidedKN0X/TapoCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Local network only](https://img.shields.io/badge/network-local%20only-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

TapoCtrl talks to your Tapo devices directly over your Wi-Fi, with the same encrypted local protocols the official app uses on your network (KLAP, the older securePassthrough, and HTTPS for cameras). Your phone never contacts TP-Link: no account login online, no cloud, no tracking. Switch things on and off, dim and colour your bulbs, see what your plugs use, and update their firmware.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/TapoCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## Features

- **Your devices at a glance:** a grid or list of every plug and bulb with its state and a big on/off button. It refreshes when you open the app and when you pull down
- **Add by address or scan:** type a device's IP address, or let TapoCtrl scan your Wi-Fi subnet (TP-Link's local discovery broadcast plus a quick check of every address). Devices that change address are found again by their MAC address; a plug or bulb is only moved to its new address after it proves there, with your account over KLAP, that it is the same device (cameras are checked by their certificate)
- **Works with Tailscale and other VPNs:** with a VPN on, **Scan network** still finds your Wi-Fi and scans it. Away from home (no Wi-Fi), **Scan a network range** scans a range you type, such as `192.168.1.0/24`, address by address through the VPN; it needs a route to that network, such as a Tailscale subnet route. Adding a device or camera by its address works through such a route too
- **Lights:** brightness, colour temperature, and hue and saturation on colour bulbs and light strips, the presets saved on the light, a strip's 17 built-in effects and a colour bulb's Party and Relax effects
- **Energy** on plugs that measure it (such as the P110 and P115): power right now, today's and this month's energy, and an estimated cost from the electricity price you set
- **Energy graphs** on those plugs: today by the hour, the last 7 and 30 days by the day, and the last 12 months by the month, read from the plug's own energy memory (with a total, the highest bar and the estimated cost). Tap a bar for its value, and export the graph as a CSV file. Reading the history never changes anything on the plug
- **Cameras** (such as the C200): privacy mode on or off, pan and tilt step by step and the positions saved on the camera (on cameras that pan and tilt), the camera's details and restart. Each camera shows on Home with its privacy mode. TapoCtrl connects over HTTPS and trusts only the camera's own certificate, after you confirmed its fingerprint once. No live video
- **Older firmware:** plugs and bulbs that don't have KLAP yet are reached with their older protocol (securePassthrough), picked automatically. A device that has answered with KLAP once is never switched to the older protocol afterwards
- **Power strips:** every socket of a strip (P300 and similar) with its own on/off switch
- **Wall switches and dimmers** (S500, S500D, S505 and similar): on/off, and brightness on dimmers
- **Hub sensors, read-only:** the temperature and humidity, contact (open/closed) and motion sensors paired with a Tapo hub (H100, H200), with low-battery and offline marks
- **Settings on the device:** the status light on or off and auto-off (on or off, after how many minutes), where the device has them, and what it does after a power cut (shown only)
- **Time on:** how long a device was on today, in the last 7 days and in the last 30 days
- **Rooms and your own order:** put devices in rooms, show one room at a time, and arrange the list the way you like (kept on this phone only)
- **Settings file:** export TapoCtrl's settings and device list to a file and import them on another phone (never your Tapo account)
- **Rename** a device (the name is saved on the device, so it shows in the Tapo app too)
- **Device info:** model, firmware and hardware version, MAC address, Wi-Fi signal and IP address
- **Firmware updates:** TapoCtrl asks the device whether TP-Link has a newer version, shows the version and release notes, and on your OK tells the device to install it. You can follow the download, install and restart. The device reboots at the end, so a plug briefly cuts power to whatever is plugged into it
- **Never switches anything on its own:** opening the app, refreshing, scanning and adding devices only read their state. A device changes only when you tap its own control (power button, slider, rename) or confirm a firmware update. No schedules, automations or "restore last state"
- **Your account stays on the phone:** you enter your Tapo email and password once; TapoCtrl keeps only the hashes the devices check, encrypted with a key in the phone's secure hardware. The email is never stored, and the password only if you turn on "Keep the password for older firmware" (see [What is stored](#what-is-stored-and-why))
- **App lock** with its own PIN and optional fingerprint unlock, and **Hide in screenshots** / **Hide in recent apps** (all off until you turn them on)
- **In English, Dutch, French or German:** TapoCtrl follows your phone's language. With the phone set to Dutch it is in Dutch (written for Flanders, informal "je"), set to French it is in French (formal "vous", as Android itself), set to German it is in German (informal "du"); otherwise English. You can also pick one of these for TapoCtrl alone under Android's Settings > Apps > TapoCtrl > Language. Model names, units and technical terms stay as they are
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, high contrast, one violet accent, Space Grotesk and JetBrains Mono

## What works, and what doesn't yet

- **Devices with current firmware** (the KLAP protocol): Tapo plugs (P100, P105, P110, P115 and similar), bulbs (L510, L530, L630 and similar) and light strips (L900, L920, L930). Kasa devices that use KLAP may work for on/off.
- **Older firmware** that uses the earlier "securePassthrough" protocol: TapoCtrl switches to it by itself when a device has no KLAP. That happens only for a device TapoCtrl has never seen speaking KLAP: securePassthrough doesn't prove which device is answering, so a device that spoke KLAP before and suddenly answers like older firmware gets an error ("Protocol changed") instead, and nothing is sent. If you really downgraded its firmware, remove it and add it again. Devices that announce login version 2 need only hashes; others (login version 1) check the password itself, which TapoCtrl only has if you turned on "Keep the password for older firmware". Not tested on a real device yet.
- **Cameras** (C100, C200, C210 and others): privacy mode, pan and tilt, saved positions and restart. Three logins are supported, picked by TapoCtrl: TPAP (SPAKE2+, recent firmware), the "encrypt_type 3" login, and the older hashed login. No live view (RTSP), recordings, detection settings or saving new positions. Which way the left/right arrows turn **needs confirming on your device** (the open-source projects disagree). Not tested on a real camera yet. A camera whose firmware asks for a login variant TapoCtrl doesn't know says so and stops before anything derived from your password is sent; the message names the login step and what the camera asked for (selectable on the camera's page, so it can be copied into a report).
- **Power strips** (P300 and similar): each socket can be switched. Energy is shown for the strip as a whole where it measures it.
- **Hubs and sensors:** temperature/humidity (T310, T315), contact (T110) and motion (T100) sensors are shown read-only through their hub. Buttons (S200) are listed without readings. Sensor settings, alarms and the hub's own siren aren't supported.
- **After a power cut:** shown as the device reports it, not changed: the command to change it couldn't be confirmed from established open-source Tapo projects, so it shows "Needs confirming on your device".
- **Schedules, timers and scenes** aren't supported (TapoCtrl never switches anything on its own).
- **Setting up a new device** (joining it to your Wi-Fi) still needs the Tapo app once. After that, TapoCtrl can do the rest.
- **Remote control from outside your home** isn't possible: TapoCtrl only works on the same network as your devices. That's the point.

**Tested on:** no Tapo device has been controlled end to end yet. A C200 was added with 0.6.0 and stopped at its login with "doesn't support yet"; 0.7.0 handles more of the login variants cameras use and says exactly which step stopped if one still does. No older-firmware device has been contacted. The protocol code is checked with unit tests against independently computed values, and during development a real P110 was found by the scan (both ways) and answered the first step of the handshake; it hasn't been switched or read with a real account yet. Please [report](https://github.com/VoidedKN0X/TapoCtrl/issues/new/choose) which devices work for you.

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- Your phone on the same Wi-Fi network as your Tapo devices, or connected to it through a VPN with a route to it (for example a Tailscale subnet router on your home network)
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
2. **Add devices:** tap **+**, then **Scan network**, and **Add** next to each device. Or type a device's IP address (your router lists it; giving the device a fixed address there keeps it the same). For a camera, TapoCtrl first shows the fingerprint of the camera's certificate; tap **Trust and add** only on your own network.
3. **Use them:** the power button on each tile switches it. Tap a tile for brightness, colour, energy, rename, device info and firmware updates.
4. **Settings** (profile picture): your Tapo account, grid or list, electricity price and currency, and privacy.

## Is it safe to install?

- **Open source.** All of TapoCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks the app's network permissions (only the two it needs) and that it has no Google libraries. The signing key is kept away from the build: one job builds the APK without any secrets, and a separate job that runs no code from this repo signs it and checks the signature against the fingerprint below.
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
| View network connections (`ACCESS_NETWORK_STATE`) | Finding this phone's Wi-Fi address and subnet for **Scan network**, also behind a VPN such as Tailscale (TapoCtrl looks at all of the phone's networks, not only the VPN). It never reads the Wi-Fi's name. |
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
- **Forgot your PIN?** It can't be recovered. The lock screen offers to reset TapoCtrl, after you confirm with your phone's own screen lock (PIN, pattern, password or fingerprint) if it has one, so someone holding your unlocked phone can't simply reset it. The reset it forgets your account hashes and your device list, resets its settings and turns the lock off. Your devices themselves aren't changed.

## Privacy

**Telemetry-free and cloud-free.** TapoCtrl has no analytics, crash reporting, usage stats or tracking of any kind, and **never contacts TP-Link or any other server**.

- **Local network only.** Every connection goes to an address you added or the scan found, and TapoCtrl checks before connecting that it's a private or link-local address (10.x, 172.16–31.x, 192.168.x, 169.254.x, the shared range 100.64–127.x that carriers and Tailscale use and that is never routed on the internet, or IPv6 fc00::/7 and fe80::/10). Host names are never looked up. Proxies and redirects are never followed.
- **Plain HTTP, encrypted content.** Tapo devices only speak HTTP on port 80, so TapoCtrl allows unencrypted HTTP connections (Android's network security config can't limit that to address ranges; the check above does). What's sent is encrypted with KLAP: AES-128 with a key both sides derive from your account during each session's handshake.
- **Your Tapo account:** see [What is stored and why](#what-is-stored-and-why). Everything is encrypted with AES-256-GCM using a key in the Android Keystore (in StrongBox, the phone's separate security chip, when it has one). With KLAP and the cameras' newer logins, devices prove they know the same account and TapoCtrl proves it back, without the hash itself crossing the network.
- **Cameras** use HTTPS with a certificate the camera made itself. When you add one, TapoCtrl shows the certificate's SHA-256 fingerprint and adds the camera only after you confirm it; from then on it accepts only that exact certificate for that camera and stops if it changes. Importing a settings file never brings a certificate along: cameras from a file must be added again.
- **Only your own account.** TapoCtrl has no built-in, blank or setup accounts. If a device doesn't accept your account, TapoCtrl says so and stops; it tries nothing else. Without a saved account it doesn't contact your devices at all (only **Scan network** looks for them, without any account data).
- **Scan network** sends TP-Link's discovery packet (with a throwaway key made on the phone for that scan) as a broadcast and to each address of your Wi-Fi's /24 subnet, and a random 16-byte handshake start to each address. **Scan a network range** does the same to each address of the range you type (no broadcast; private ranges only, at most a /22 = 1022 addresses, up to 48 addresses at a time, with a progress bar and **Stop**); the last range is remembered on the phone. Nothing about you or your account is sent, and scanning never changes a device. When a saved plug or bulb turns up at a new address, TapoCtrl reads its details there over KLAP (which only works with your account) before moving it. TapoCtrl never switches between addresses or networks by itself.
- **Your device list** (addresses, names, models and MAC addresses of the devices you added) is stored encrypted on the phone as well: AES-256-GCM with a random key that is itself protected by a key in the Android Keystore. That protects it when the app's files are copied off the phone, on top of Android's own storage encryption.
- **Energy graphs** are read from the plug itself (get_energy_data, or the history in get_energy_usage on older firmware), on your network. Nothing is stored or sent elsewhere.
- **Firmware updates** are fetched by the device itself from TP-Link, as with the Tapo app. TapoCtrl only asks the device to check and to install; it never sends the device a download address and never touches the firmware.
- Your devices, settings and account hashes are in TapoCtrl's private storage. Android backups don't include them, and TapoCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## What is stored and why

All of this is in one file in TapoCtrl's private storage, encrypted with AES-256-GCM under a key in the Android Keystore, left out of backups, and dropped from memory when the app lock locks. Your email is never stored.

| Value | Derived from | Why |
| --- | --- | --- |
| SHA-256(SHA-1(email) + SHA-1(password)) and MD5(MD5(email) + MD5(password)) | email and password | The two KLAP hashes current plug and bulb firmware checks |
| MD5(password) and SHA-256(password) | password | Cameras: the encrypt_type 3 login, the older hashed login, and TPAP's passcodes are made from these |
| SHA-1(email) and SHA-1(password) | email, password | Older firmware (securePassthrough), login version 2 |
| **The password itself**, only if you turn on "Keep the password for older firmware" | — | Older firmware with login version 1 sends the password itself (Base64, inside its encrypted session). There's no way around it for those devices. Off by default |

An account saved with TapoCtrl 0.5 or earlier has only the two KLAP hashes. Plugs and bulbs keep working; for cameras and older firmware, enter the account again.

The password (when you keep it) and the hashes above are only ever sent to your own devices, inside their encrypted logins: securePassthrough's login (login version 1 sends the password, version 2 a SHA-1 hash, protected by a session key the device sends encrypted with a throwaway RSA key), a camera's older hashed login (the MD5 hash, over the pinned HTTPS connection), or as proofs that never reveal the hash (KLAP, encrypt_type 3, TPAP).

## How TapoCtrl is made

TapoCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

TapoCtrl isn't made by or affiliated with TP-Link. Tapo is a trademark of TP-Link. The KLAP, securePassthrough and camera protocols were documented by the open-source community (python-kasa, plugp100, pytapo and the tapo crate); TapoCtrl is its own implementation, checked against their test vectors.

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
| **TapoCtrl** (this app) | Tapo plugs, bulbs and cameras on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

TapoCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).
