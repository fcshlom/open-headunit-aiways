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

The turn card snapshots now cover every maneuver at 300 m, 100 m and at the turn itself, where Waze shows lanes and junction details, not only roundabouts. Roundabouts still also get one at 800 m. The cap is 80 a day.

## The roundabout icon from the screen (4.0.0-aiways.14)

Before a roundabout Waze sends the car one generic picture, the same for every roundabout: one picture for the three roundabouts of 23 November. The icon on Waze's own turn card on the Android Auto screen already shows the real exit, from 1.7 km and 450 m before the roundabout on (the `[turncard]` snapshots). The real exit picture reaches the car only inside the roundabout.

`AiwaysTurnIcon` cuts that icon from the screen. It does this only while the next maneuver is a roundabout, once a second. The box sits right of Android Auto's rail, left of the distance: x 58..92, y 22..50 of the 960 x 330 snapshots, kept as fractions of the view. A crop counts only with a dark border (the card) and 2–50 % bright pixels inside (the white icon), so nothing is sent when Waze shrinks the card for an alert. It is sent as `com.aiways.headunit.TURN_ICON` with the PNG in `icon` and the event in `event`, under the navigation broadcast's signature permission: when the icon changes, and an unchanged one again every 2 s (the launcher keeps an icon for 3 s). The view is read at half size into one reused bitmap on the main thread, and the crop is checked in one reused array; the PNG is compressed and sent on a worker thread. The navigation callback only posts the event to the main thread, and the TextureView stays private to `AiwaysTurnCard`, which reads it for `AiwaysTurnIcon`. The log shows `[turnicon] icon WxH sent` or `no icon on the screen: <why>` once per roundabout. The launcher (4.80.0) draws it on the driver's display.

## The roundabout's exit read from the screen (4.0.0-aiways.15)

On the drive of 23 November the icon of 4.0.0-aiways.14 looked blurred on the driver's display: it is only about 34 x 28 pixels on the screen. When another app's page (Spotify) or a notification covered the corner, part of that was sent instead.

Now `AiwaysRoundaboutReader` reads the exit's direction from the icon, and the launcher draws its own sharp icon at that angle. Waze draws a roundabout as a ring, with the entry as a stem from below and the exit as an arrow leaving the ring. The reader finds the ring (the brightest rim around a dark centre), checks the stem below it, and takes the brightest ray outside the ring as the exit. No second ray may be bright. The angle is in degrees anticlockwise from the entry (right 90, straight 180, left 270), rounded to 45.

- **Checked on:** the crops of 23 November. The left exits (morning and evening) read 270, the right exit 90, and the straight one 180. Spotify's pages, a WhatsApp notification and a plain turn arrow read nothing. These crops are the unit test `AiwaysRoundaboutReaderTest`.
- **Confirmed:** an angle counts only when two reads in a row agree. It is then kept for the rest of that roundabout, also while another app or a notification covers the corner.
- **Forgotten:** when the next maneuver is no roundabout, after the roundabout itself (event 12), and when the distance jumps up (the next roundabout).
- **Threads:** the main thread only reads the screen at half size and copies the crop out. The reading and the broadcast run on a worker thread.
- **Sent:** every second while an angle is known, as `com.aiways.headunit.TURN_ICON` with the angle in `exit_deg` and the event in `event`. The picture (`icon`) is no longer sent.
- **Log:** `[turnicon] event 11: exit 270 (265) ring … stem … out …/…` when the angle is found or changes, and `no exit: <why> …` once per roundabout when the screen shows none.

## Wireless Android Auto after the car is switched off (4.0.0-aiways.16)

On 24 November wireless Android Auto failed for the whole noon drive. The app reached the phone's head unit server (port 5277) 47 times between 13:09 and 13:21, on the T-Box WiFi and on the phone's hotspot. Every time the phone accepted the connection and never answered (`session state failed (peer_silent)`). At 13:21 the phone was plugged in by USB and projected at once, and wireless worked again on the afternoon drive.

The likely cause is the end of the morning drive of 23 November: the car was switched off at 07:28 in the middle of a wireless session. The head unit runs on for 20–27 s after the car goes off and then loses power, but it sends none of the ACC intents other units send, so the app never closed the session. A session that vanishes without a ByeBye leaves Android Auto's head unit server on the phone waiting for that peer, with no timeout, and it answers no one after it. Only a USB session or a phone restart clears that.

- **Car off and on from the launcher:** the launcher (4.82.0) watches `CARSET_PWR_STS` (3 on, 1 off) and sends `com.aiways.headunit.CAR_POWER` with `on`, under the signature permission `com.aiways.permission.HEADUNIT_LINK` (`AiwaysCarPower`). On off, `AapService` handles it like an explicit ACC off: it closes every route with a ByeBye while the link still works. Until the car is on again nothing dials the phone: no reconnect, no discovery sweep, and a sweep already running dials nothing. On on, discovery starts again. Log: `WakeDetect: car switched off (launcher, CARSET_PWR_STS)`, `AapService: <trigger> with a live session — closing it now`, `Discovery not started — the car is off`.
- **A hint on the launcher's screen:** after 3 silent attempts in a row with a head unit server (any address), the app asks the launcher to show "Wireless Android Auto: the phone is not answering. Plug the phone in by USB once" (`AiwaysServerHint`, `com.adayo.radio.AA_SERVER_STUCK`, same permission), again every 5 minutes while it lasts. A session that projects ends it. The app's own banner for this sits on its main screen, which nobody sees while driving. Log: `[hint] the phone's head unit server at <ip>:5277 accepted N connections in a row without answering`.
- **The end of a session in the day log:** the car runs the log at VERBOSE, and the per-frame debug lines (`SSL Decrypt`, `RECV: VIDEO Media Data`, `VIDEO Codec/Media Data Ack`) were 40,000 of the 46,000 lines of the 23 and 24 November files. They used up AppLog's 5 MB of the day in the first drive, so the 23 November file stops at 05:46 although the car drove until 07:28. Now a DEBUG or VERBOSE line is written only while its kind (the line without its thread and numbers) has had fewer than 20 lines in the last 10 minutes; the next one after a gap says `(+N like this left out)` (`DayLogThrottle`). INFO and above always go through. Replayed on the two files, AppLog's lines shrink from 4.7 MB to 206 KB (23 November, 05:11–05:46) and from 5.2 MB to 402 KB (24 November, 13:05–13:27), so the 5 MB of the day now last for hours of driving.
