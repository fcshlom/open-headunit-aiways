# Open Headunit for Aiways (fork)

A version of Open Headunit made for one car only: the Aiways U5 with the Adayo head unit. Goal: **Android Auto connects by itself, without the driver touching anything**. It first ships as a separate app, and later gets embedded in the home screen ([fcshlom/Aiways_Luncher](https://github.com/fcshlom/Aiways_Luncher)).

## What we know about the car

| Topic | Status |
|---|---|
| Android | 4.4.2 (API 19) on a Freescale i.MX6 (`SABRESD-MX6DQ`). Only the `github` flavor (minSdk 16) installs. `playstore` needs minSdk 21 |
| USB | The socket is broken: no wired Android Auto, no USB dongle, no adb |
| Wi-Fi | Joining the phone's hotspot as a client works. The stock firmware also runs the unit's **own hotspot** at boot (hidden SSID `DMSLVXMAXBAXLR800010`, for the driver-monitoring camera, `com.adayo.system` `SystemManager`). The launcher turns it off to join the phone. So the unit's SoftAP itself is stable |
| Native mode (the default) | **Crashes the unit on the spot** (full reboot) as soon as wireless starts. Matches upstream's `BootLoopPolicy` (MT8163). Since the stock firmware runs a hotspot without trouble, the suspects are Wi-Fi Direct and Android's dead Bluetooth stack, not the hotspot |
| Headunit Server → Manual | **Works without a crash**: TCP to the phone (`IP:5277`) over the phone's hotspot. The phone needs "Start headunit server" in Android Auto's developer settings |
| Bluetooth | Two radios, neither usable through Android's API. **Android's adapter never comes up** (stuck in `turning_on`, placeholder address `22:22:64:F1:E3:08`, launcher 4.56 probe, 3 Nov). The real one is **Adayo's module on a UART** (`com.haoke.uart.UartCom.openBTJni`, driven by `com.haoke.btjar` from the framework). The phone pairs with it for calls and music. Its service (`IBTService`) has calls, contacts, music and pairing, and **no SPP/RFCOMM API**. The T-Box has a third radio, owned by `appl.bin` (the eCall process) |
| Screen | Wide (about 1920×720) |
| T-Box | `192.168.1.1`. The unit reaches it over USB (`usb0`, DHCP, a different `192.168.1.x` on every boot). The T-Box **bridges its Wi-Fi AP (`MyAiways-…`) and that USB link into one `bridge0`**. A phone on the T-Box AP reached the unit's port 5288 directly (verified 3 Nov) |

## Wireless the standard way (research, November 2026)

Today the driver turns on the phone's hotspot and starts Android Auto's developer "headunit server". That server is a debug tool: it sometimes stops answering after a drop, which is why it has to be stopped and started again. A real car does it differently. This section is what we found and the plan that follows from it.

### How a real car starts wireless Android Auto

There are two standard ways, and both end in the same place: Android Auto on the phone stores a **known-car record** (network, TCP endpoint, Bluetooth identity) and from then on starts by itself.

1. **Bluetooth handshake** (every car and dongle). The car registers an RFCOMM service with UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66` (channel 8 on the dongles) and connects to the paired phone. The phone opens RFCOMM. Car → `WifiStartRequest{ip, port}`, phone → `WifiInfoRequest`, car → `WifiInfoResponse{ssid, key, bssid, security_mode, access_point_type}`, then `WifiStartResponse` and `WifiConnectStatus`. Framing: `[u16 BE length][u16 BE id][protobuf]`. The phone joins the car's Wi-Fi and opens TCP to `ip:port`. After the handshake, the dongles switch their Bluetooth off so calls stay on the car's own Bluetooth. Sources: [WirelessAndroidAutoDongle](https://github.com/nisargjhaveri/WirelessAndroidAutoDongle), [aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs).
2. **Setup QR / deep link** (newer head units). The head unit shows `https://androidauto.com/projection/setup?data=<base64url ProjectionDeepLinkData>`, with fields `ssid`, `bssid`, `passkey`, `wpp_tcp_ip`, `wpp_tcp_port`, `bluetooth_mac` and `security_mode` (`wireless.proto`). Scanning it once writes the record. Afterwards, **whenever the named Bluetooth device connects (HFP)**, the phone joins the stored network and dials `wpp_tcp_ip:wpp_tcp_port` over TCP (WPP over TLS, the head unit is the TLS client). It is then told where the projection session runs (5288). Upstream implements both sides: `ProjectionDeepLink`, `ProjectionQrPolicy`, and `WppTcpServer` on port 5299, which Native mode starts before any Bluetooth check. In upstream's words, the Bluetooth address "is an identity, not a route": the phone requires that device to be connected but does not check that it is the head unit.

### Why route 2 fits this car

Route 1 needs an RFCOMM service on a radio the phone talks to, and this unit has none we can use: Android's adapter is dead, Adayo's module has no RFCOMM API, and the T-Box radio lives inside `appl.bin`. Hooking `libbluegate.so` there crashed `appl.bin` (and with it eCall) in the November experiment (`aaw_boot` v0.5, removed in aiways-monorepo `a5b1486`).

Route 2 needs no radio of ours at all:

| Record field | Value for this car |
|---|---|
| `ssid`, `passkey`, `bssid` | The T-Box AP (`MyAiways-…`, from `tbox_daemon.conf`; BSSID from a Wi-Fi scan). It is up whenever the car is on, and `tbox_daemon` keeps it up |
| `wpp_tcp_ip`, `wpp_tcp_port` | The unit's address on `bridge0` (over `usb0`), port 5299. **This must not change between drives** (see stage A) |
| `bluetooth_mac` | **Adayo's module**, which the phone already connects to over HFP when the car starts (`IBTService.getBTAddress()`, transaction 31). The AAP Bluetooth service should announce the same address as `carAddress`, so calls stay on the car |

The unit itself then only runs two TCP listeners: no Wi-Fi Direct, no hotspot, no Android Bluetooth, so none of the things that crashed Native mode. The unit's own Wi-Fi stays free (for example, for the phone's hotspot as its internet), and Android Auto's traffic reaches it over the USB link.

**Not proven yet:** no upstream field report confirms that the record starts projection on its own. That is why stage B below comes first and needs no projection code.

### Plan

- **A. Measure (no code, or launcher logging only).**
  - Network and video quality on the target path: phone hotspot off, phone joined to `MyAiways-…`, developer server on. Headunit Server → Auto should find the phone on `192.168.1.x` over `usb0`. Watch the latency lines from "Reading the logs".
  - Log Adayo's module address, the unit's `usb0` IP and MAC on every boot, and the T-Box AP's BSSID. If the `usb0` MAC is stable, a DHCP reservation on the T-Box fixes the address. If not, `tbox_daemon` relays `192.168.1.1:5299` to the unit: only the small WPP control channel needs a fixed address, since the projection endpoint is sent fresh in every handshake.
- **B. Prove the trigger (small, no risk to the car).** Build the deep link from the values in A and show it as a QR, with a plain listener on 5299 that only logs who dials. Scan once, switch the car off and on. Success means the log shows the phone dialling 5299 from `192.168.1.x` right after Adayo's Bluetooth connects.
- **C. Build it.** A car-network Native variant: credentials from config instead of a group or hotspot, all Android Bluetooth paths off, the WPP server and the 5288 listener bound on `usb0`, `bluetooth_mac` and `carAddress` set to Adayo's module, and a QR screen. The launcher can supply the module address and the T-Box AP details over `com.aiways.headunit.CONFIG`. Keep Headunit Server as the fallback.
- **D. Internet for the unit.** With the phone on the car's Wi-Fi, the unit loses the phone's hotspot unless the phone can run both at once (many recent phones can). Otherwise, put a data SIM in the T-Box.
- **The phone's own data stays on cellular.** An earlier test joined the phone to `MyAiways-…` from the Wi-Fi settings (for the developer server), and the phone lost its data. A network joined like that, especially after "stay connected" on the no-internet prompt, can become the phone's main network. In the standard flow, Android Auto brings up its own connection to the car's Wi-Fi only for the projection (it pins the BSSID), and every other app stays on cellular. That is how every car with wireless Android Auto works: none of their Wi-Fi networks has internet. It is also how our phone bridge already reaches the T-Box (`WifiNetworkSpecifier` plus binding, aiwaysapp `MainActivity`). So remove `MyAiways-…` from the phone's saved Wi-Fi networks and let only Android Auto and the bridge join it.
- **If B fails:** do route 1 on an independent radio instead of the T-Box. That means a classic-Bluetooth board (ESP32-WROOM, not the bridge's BLE-only ESP32-S3, or a Pi Zero 2 W with BlueZ) that only runs the RFCOMM handshake. It points the phone at the same T-Box AP and the unit's address. Open question: such a board has to connect a profile to the phone to wake Android Auto (the dongles use HSP), which briefly competes with the car's HFP.

### CarPlay

Wireless CarPlay needs things only Apple licensees have: an MFi authentication chip, iAP2 over the car's Bluetooth (in its EIR), and a CarPlay receiver stack. There is no open-source receiver. The unit has a stock CarPlay app (`com.adayo.carplay`; `com.adayo.carmanager` only tracks its state), and wired CarPlay goes over the broken USB socket. Realistic routes, in order:

1. Export `com.adayo.carplay` (as was done for the other Adayo apps) and see whether it supports wireless at all and over which radio.
2. Repair the USB socket. That also brings back wired Android Auto and the standard Android Auto dongles. Then a wireless CarPlay adapter (Carlinkit type) works with the stock wired CarPlay app, with normal pairing and automatic reconnects.

Without one of these, there is no software-only route to CarPlay on this unit.

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
