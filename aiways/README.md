# Aiways build: self-update and logs

`com.aiways.headunit` is this fork's own package, so it installs next to the stock Open Headunit. Like the head-unit launcher ([fcshlom/Aiways_Luncher](https://github.com/fcshlom/Aiways_Luncher)), it updates itself and sends its logs to the own backend. Nothing is entered on the car: the launcher hands over the server, the API key and the T-Box time.

## How it works

| Part | Where | What |
|---|---|---|
| Settings | `AiwaysConfigReceiver` | The launcher's broadcast `com.aiways.headunit.CONFIG`, every ~15 min. It is guarded on both sides by the signature permission `com.aiways.permission.HEADUNIT_LINK`, so this app must be signed with the launcher's preview key. |
| Logs | `AiwaysLog` | Every line AppLog writes, plus this package's own events, go to `Android/data/com.aiways.headunit/files/logs/headunit-YYYY-MM-DD.log`. Kept 7 days. |
| Upload | `AiwaysSyncService` | Runs on every broadcast and sends what the day files gained (`POST /launcher/logs`). The launcher repo's `car-logs` job copies the files to GitHub (`car-logs` branch). |
| Update | `AiwaysSyncService` | At most every hour: `GET /app/latest?abi=headunit`. A newer build is downloaded, its sha256 is checked, and it is installed with `su -c pm install -r` (no tap). Without root, Android's installer opens and asks once. |
| Navigation | `AapNavigationHelper` | Every turn Android Auto reports (any nav app) goes out as the broadcast `com.andrerinas.headunitrevived.NAVIGATION_UPDATE`, guarded by the signature permission `com.andrerinas.headunitrevived.permission.NAVIGATION_UPDATE`. The launcher holds it and shows the next turn on its control bar. Until 4.0.0-aiways.9 the manifest declared the permission under another name, so nobody could receive the broadcast. |
| Default mode | `WifiLauncherMode.DEFAULT` | Headunit Server → Auto: it finds the phone on the T-Box Wi-Fi or on the phone's hotspot. Native (Wi-Fi Direct / its own hotspot) reboots this head unit. |

Before the launcher has sent the T-Box time, nothing is uploaded: TLS needs the real date, and the head unit's clock is years off.

## Publishing (`.github/workflows/publish-headunit.yml`)

On every push to `main` or `claude/**`: build `githubDebug`, sign it with the launcher's key, and upload it to channel `headunit`.

- Raise `VERSION_CODE` in `aiways/version.properties` with every change. The backend takes 1..999 and the car installs only a higher build.
- Secrets in this repository:
  - `AIWAYS_SIGNING_P12_BASE64`: `base64 -w0 tools/preview/preview-debug.p12` from Aiways_Luncher.
  - `AIWAYS_ADMIN_USER`, `AIWAYS_ADMIN_PASSWORD`: same as in Aiways_Luncher.
- The backend needs the `headunit` channel (aiways-monorepo PR #65).

## Known settings for this head unit

| Setting | Value | Why |
|---|---|---|
| Rendering backend (view mode) | **TextureView** (the default) | With SurfaceView, touches answered about 3 s late, most of all while navigating. Switching back to TextureView fixed it (reported from the car, November 2026). Do not change the default to SurfaceView. |

If the picture reacts late again, check this setting first. With TextureView the logs of 21 Oct show the unit's own path is short: hardware decode about 50 ms, a video queue of a few frames, 22 of 30 frames per second displayed.

## Reading the logs

The day files (`headunit-YYYY-MM-DD.log`) reach the `car-logs` branch of fcshlom/Aiways_Luncher under `logs/<owner>/`. They arrive only while the launcher sends its `com.aiways.headunit.CONFIG` broadcast (launcher 4.69.6 and later).

A tap's round trip, stage by stage, and the line that measures each one. All of them print about every 5 or 30 s during a session:

| Stage | Line to search for | Healthy on this unit |
|---|---|---|
| Touch screen to the app, send queue, socket write | `touch latency over` (since aiways.8; only in windows with a touch) | a few ms each |
| Uplink blocked | `uplink blocked on` (only when a write stalls) | absent |
| Phone to unit: frames queued on the way | `video link lag over` (since aiways.8) | well under 200 ms; it is measured against the best moment of the session |
| What the phone sends | `inbound rate over` | up to about 490 kB/s of video while a map moves |
| Reader and video queue | `transport dispatch over` | `videoQueue` under 10, `videoShed=0`, `blocks=0` |
| Decoder | `Throughput over` | 29-30 fps, `decodeLatency` about 50 ms, `dropped=0` |
| Display | `TextureProjectionView: displayed` | 22 fps, `longFrames=0` |
| Which renderer is in use | `Projection backend: viewMode=` | `TEXTURE` |
| Session start and end | `session state` | `disconnected (link_lost)` means the Wi-Fi link dropped |

If taps answer late: find the minute in the log and read the table top to bottom. The first stage that is far from its healthy value is where the time goes. If every stage is healthy, the delay is inside the phone.

## Wireless setup test (4.0.0-aiways.10)

Stage B of "Wireless the standard way" in [AIWAYS.md](../AIWAYS.md): does Android Auto dial this unit by itself, once the phone has scanned the setup QR, when the phone's Bluetooth connects to the car? The test only listens; it never starts a projection, so Headunit Server keeps working as before.

| Part | Where | What |
|---|---|---|
| Screen | `AiwaysWirelessSetupActivity`, icon "AA wireless test" in the launcher's app list | Reads the T-Box AP's name and key from `/usrdata/tbox_daemon.conf` (FTP), its BSSID from a Wi-Fi scan, this unit's `usb0` address, and Adayo's Bluetooth address (`IBTService` 31). Every field can be corrected by hand. "Show QR and start" draws Android Auto's setup QR (`ProjectionDeepLink`, port 5299) and arms the listener |
| Listener | `AiwaysWppProbeService` | Port 5299, only while armed. The launcher's CONFIG broadcast restarts it after a reboot. Each dial is logged, then TLS is opened as the client (the role the phone expects) to log the phone's certificate, and the connection is closed |

In the car:

1. Car on, phone connected to the car's Bluetooth as usual. Open "AA wireless test" and check the fields.
2. "Show QR and start", then scan the QR with the phone's camera. Android Auto opens: confirm.
3. Switch the phone's Bluetooth off and on, with the car still on. The unit's `usb0` address changes on every boot, so a reboot would send the phone to an old address. That is why this step does not switch the car off.
4. Success is a `DIAL from 192.168.1.x` line on the screen, followed by `TLS ... ok`.
5. Undo: on the phone, Android Auto settings > Previously connected cars > forget the car. Then "Stop the test" on the unit. Until then the phone may keep dialling the unit whenever it connects to the car.

In the day log, look for `[wpp-test]`: what was read (the key never goes to the log), the QR's values, and every dial.

## Turn images for the driver's display (4.0.0-aiways.11)

The launcher draws the next turn on the driver's display. From the codes alone it cannot draw a roundabout right: "exit 2" is a left turn in a roundabout with three arms and straight ahead in one with four, and the phone has sent no exit angle so far (`turn_angle` 0 in every launcher log). So the navigation service is now announced as `CustomImageSupported` with 256 x 256, 32-bit images, instead of `ImageCodesOnly`. A nav app that honours it sends its own picture of the next turn in `NextTurnDetail.image` (PNG).

The `NAVIGATION_UPDATE` broadcast carries four new extras:
- `turn_image`: the PNG bytes, only when the phone sent a picture.
- `maneuver_type`: the instrument-cluster `NavigationManeuver.NavigationType` of the next step, or -1 (34 = roundabout anticlockwise, 35 = the same with an exit angle).
- `lane_shapes`: the directions of the highlighted lanes of the next step (`LaneDirection.Shape`: 1 straight, 4/5 left/right, ...). Waze gives some roundabouts "turn left / straight / right" instructions instead of an exit number; the highlighted lane then says which way the exit goes, even without an angle.
- `cue_text`: the nav app's own text for the next step (`NavigationCue.alternate_text`), or empty.

`turn_number` and `turn_angle` now come only from fields the phone really set. An unset proto2 int reads 0, which used to hide the legacy `NextTurnDetail` values. The log line `Nav: NextTurnDetail` shows `image=<n> bytes` or `image=none`.

## Snapshots of the turn card (4.0.0-aiways.12)

Waze sends the car only "enter roundabout" and one picture: no exit number, no lanes, no angle (every turn in the launcher's log of 22 November). That picture once showed a left-exit roundabout with its exit on the right, while Waze's own turn card on the Android Auto screen was right and showed the lanes as well. The plan is to show that card on the driver's display. Step 1 is finding where Waze draws it.

`AiwaysTurnCard` takes a snapshot of the projection (the TextureView, scaled to 960 px wide, JPEG) and writes it to the log as `[turncard] frame WxH event=<n> in <m> m jpeg=<base64>`. It does this when the next maneuver changes, and at a roundabout also at 800, 300, 100 and 0 m. There are at most 40 a day, because a frame shows the map, so where the car is. Only the TextureView projection, the Aiways default, can be read back. Step 2 crops the card from the frame and sends it to the launcher.

## Room for this app's own log lines (4.0.0-aiways.13)

The day log was capped at 5 MB. The projection's own debug lines (an ack for every video frame) filled that by the first drive of the day. Both the 22 and 23 November files stopped at exactly 5,242,970 bytes, and the turn card snapshots of the 05:47–06:05 drive on 23 November were lost with everything else. Now AppLog's lines still stop at 5 MB a day, and this package's own events (`[turncard]`, `[sync]`, `[wifi]`, ...) go on up to 15 MB a day. The backend takes up to 20 MB per day file.
