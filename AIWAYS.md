# Open Headunit for Aiways (fork)

A version of Open Headunit made for one car only: the Aiways U5 with the Adayo head unit. Goal: **Android Auto connects by itself, without the driver touching anything**. It first ships as a separate app, and later gets embedded in the home screen ([fcshlom/Aiways_Luncher](https://github.com/fcshlom/Aiways_Luncher)).

## What we know about the car

| Topic | Status |
|---|---|
| Android | 4.4 (API 19). Only the `github` flavor (minSdk 16) installs. `playstore` needs minSdk 21 |
| USB | The socket is broken: no wired Android Auto, no USB dongle, no adb |
| Wi-Fi | Joining the phone's hotspot as a client works |
| Native mode (the default) | **Crashes the unit on the spot** (full reboot) as soon as wireless starts. Matches upstream's `BootLoopPolicy` (MT8163). Not yet checked whether Wi-Fi Direct or the hotspot is the culprit |
| Headunit Server → Manual | **Works without a crash**: TCP to the phone (`IP:5277`) over the phone's hotspot. The phone needs "Start headunit server" in Android Auto's developer settings |
| Bluetooth | The launcher talks to Adayo's Bluetooth service (`com.adayo.aidl.IBTService`), so Android's Bluetooth stack may be a separate module. Not checked yet |
| Screen | Wide (about 1920×720) |
| T-Box | `192.168.1.1:9000`, the link type is not known yet |

## Stages

### 0. Discovery (read-only, no crash risk)
The launcher's black box (Live data → Black box → Collect now) writes `connectivity.txt`:
- Android `BluetoothAdapter`: on or off, paired phones, whether the phone offers wireless Android Auto (UUID `4de17a00-…`), and whether RFCOMM can listen.
- Network interfaces, routes and ARP, i.e. how the unit reaches the T-Box.
- Saved and nearby Wi-Fi networks: does the T-Box put up a Wi-Fi network in the car?

Controlled tests with the stock app (you know how to uninstall it after a crash):
- Native with the **Hotspot** transport instead of Wi-Fi Direct: does that crash too?
- The hotspot from Android's own settings: is it stable?
- On the phone: does the headunit server stay up between drives?

**Decision after stage 0:**
- **Path A, Bluetooth-started**, like a real car: if Android Bluetooth works and there is a network the phone can join without the unit putting it up (the T-Box, or a stable hotspot). Zero taps.
- **Path B, Headunit Server**: the unit connects by itself to the phone's gateway. The phone has to keep the server running, so it is automatic only if the server stays up.

### 1. A dedicated separate app
- A separate `applicationId` (installs alongside the stock app, which helps comparisons).
- Settings fixed in code:
  - wireless mode set to whatever stage 0 picks
  - H.264, resolution and margins for the wide screen
  - audio streams, wheel keys
- No setup wizard. Native, Wi-Fi Direct and auto-hotspot are switched off in code, not just in settings.
- The same signing key as the launcher, kept as a GitHub secret. The `NAVIGATION_UPDATE` signature permission then works as is: the launcher gets the turn arrows.
- CI that builds the `githubDebug` APK.

### 2. Automatic connection
- A service that starts on boot or ACC on, waits for the known Wi-Fi network, probes the target every few seconds, connects, and retries with backoff.
- After a crash or reboot loop it does not retry what caused it (upstream already has the idea: `BootLoopPolicy`).
- Path A: the Bluetooth handshake. The phone joins the agreed network and connects to us.

### 3. Hooking up the launcher (intents)
- Android Auto tile with the connection status, raised automatically on connect.
- Navigation bar: arrow, distance, street.
- Song title from Android Auto on the music card.
- Night mode from the car's data (T-Box).

### 4. Embedding in the launcher
- The code becomes a module in the launcher's APK. The Android Auto service runs in its own process, so a crash does not take the home screen down.
- Projection in a home-screen panel.
- Only at this stage does code we don't need get cut.
- The launcher then falls under AGPL-3.0 with published source.

## Working with upstream
- `upstream` = `andreknieriem/open-headunit`. Merge from it regularly: Android Auto protocol fixes come from there.
- Until stage 4, keep the diff small: switch things off with config and small code edits, not mass deletions.
- Car-specific changes go in their own files where possible (`aiways/`), so a merge from upstream hits few conflicts.
