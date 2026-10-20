# Aiways build: self-update and logs

`com.aiways.headunit` is this fork's own package, so it installs next to the stock Open Headunit. Like the head-unit launcher ([fcshlom/Aiways_Luncher](https://github.com/fcshlom/Aiways_Luncher)), it updates itself and sends its logs to the own backend. Nothing is entered on the car: the launcher hands over the server, the API key and the T-Box time.

## How it works

| Part | Where | What |
|---|---|---|
| Settings | `AiwaysConfigReceiver` | The launcher's broadcast `com.aiways.headunit.CONFIG`, every ~15 min. It is guarded on both sides by the signature permission `com.aiways.permission.HEADUNIT_LINK`, so this app must be signed with the launcher's preview key. |
| Logs | `AiwaysLog` | Every line AppLog writes, plus this package's own events, go to `Android/data/com.aiways.headunit/files/logs/headunit-YYYY-MM-DD.log`. Kept 7 days. |
| Upload | `AiwaysSyncService` | Runs on every broadcast and sends what the day files gained (`POST /launcher/logs`). The launcher repo's `car-logs` job copies the files to GitHub (`car-logs` branch). |
| Update | `AiwaysSyncService` | At most every 6 h: `GET /app/latest?abi=headunit`. A newer build is downloaded, its sha256 is checked, and it is installed with `su -c pm install -r` (no tap). Without root, Android's installer opens and asks once. |
| Default mode | `WifiLauncherMode.DEFAULT` | Headunit Server → Auto: it finds the phone on the T-Box Wi-Fi or on the phone's hotspot. Native (Wi-Fi Direct / its own hotspot) reboots this head unit. |

Before the launcher has sent the T-Box time, nothing is uploaded: TLS needs the real date, and the head unit's clock is years off.

## Publishing (`.github/workflows/publish-headunit.yml`)

On every push to `main` or `claude/**`: build `githubDebug`, sign it with the launcher's key, and upload it to channel `headunit`.

- Raise `VERSION_CODE` in `aiways/version.properties` with every change. The backend takes 1..999 and the car installs only a higher build.
- Secrets in this repository:
  - `AIWAYS_SIGNING_P12_BASE64`: `base64 -w0 tools/preview/preview-debug.p12` from Aiways_Luncher.
  - `AIWAYS_ADMIN_USER`, `AIWAYS_ADMIN_PASSWORD`: same as in Aiways_Luncher.
- The backend needs the `headunit` channel (aiways-monorepo PR #65).
