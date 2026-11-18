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
