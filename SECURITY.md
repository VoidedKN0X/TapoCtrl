# Security policy

Thank you for helping keep TapoCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/TapoCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of TapoCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

TapoCtrl has no server and only talks to devices on your own network. So these count:

- Any way for TapoCtrl to connect to an address outside your local network, or to TP-Link's cloud
- Your Tapo email or password being stored, or the saved hashes being readable by other apps or ending up in backups
- Mistakes in the KLAP implementation that weaken its encryption or let another device on the network pretend to be yours
- A way to make a device install firmware from somewhere other than TP-Link's own update
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN
- Anything that lets someone run code or read files they shouldn't

Weaknesses in the Tapo devices or their firmware themselves should go to TP-Link.

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
BD:56:98:B5:ED:E9:0C:99:94:60:BF:0A:A2:C7:9A:6E:63:08:B5:E7:65:C7:BC:06:56:C4:90:A7:05:BF:EB:E8
```
