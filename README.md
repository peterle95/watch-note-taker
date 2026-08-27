# Watch Note Taker

Watch Note Taker records bounded voice notes on Wear OS, transfers them to an Android phone, transcribes them through an authenticated backend, supports transcript review, and writes approved notes to an Obsidian vault folder.

The project is not yet production-validated. The JVM tests and APK builds pass, but the paired-device and SAF instrumentation checklists must pass on physical hardware before release.

## Requirements

- JDK 17 or newer
- Android SDK 35
- Android phone running API 26 or newer
- Wear OS watch running API 30 or newer
- Google Play services and a paired phone/watch Data Layer connection
- HTTPS transcription backend implementing the contract below

## Permissions

- Watch: microphone access for recording
- Phone: internet access for backend transcription
- Phone: user-selected Storage Access Framework tree permission for the vault

The phone does not contain transcription-provider credentials. A revocable device token is entered on the phone and sent only to the configured backend.

## Build

PowerShell requires the Gradle JVM property to be quoted:

```powershell
.\gradlew.bat --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536m" test :phone:assembleDebug :watch:assembleDebug
```

Configure the backend URL at build time:

```powershell
.\gradlew.bat -PBACKEND_URL=https://transcribe.example.com/v1/transcriptions :phone:assembleDebug :watch:assembleDebug
```

Release APK/AAB packaging requires the same signing key configuration for both modules. Supply all four values as Gradle properties or environment variables:

- `RELEASE_STORE_FILE`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

```powershell
.\gradlew.bat -PBACKEND_URL=https://transcribe.example.com/v1/transcriptions -PRELEASE_STORE_FILE=C:\secure\release.jks -PRELEASE_STORE_PASSWORD=... -PRELEASE_KEY_ALIAS=... -PRELEASE_KEY_PASSWORD=... :phone:assembleRelease :watch:assembleRelease :phone:bundleRelease :watch:bundleRelease
```

Release packaging fails if signing values are absent or incomplete. Keystores, `local.properties`, and release signing property files are ignored by Git.

Debug APKs are generated at:

- `phone/build/outputs/apk/debug/phone-debug.apk`
- `watch/build/outputs/apk/debug/watch-debug.apk`

## Install And Pair

1. Pair the Wear OS watch with the Android phone using the Wear OS companion flow.
2. Enable USB or wireless debugging on both devices.
3. List devices with `adb devices` and note each serial.
4. Install the phone app:

```powershell
adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk
```

5. Install the watch app:

```powershell
adb -s <watch-serial> install -r watch/build/outputs/apk/debug/watch-debug.apk
```

6. Confirm both apps use application ID `com.peterle95.watchnotetaker` and APKs signed by the same key:

```powershell
apksigner verify --print-certs phone/build/outputs/apk/debug/phone-debug.apk
apksigner verify --print-certs watch/build/outputs/apk/debug/watch-debug.apk
```

The phone advertises the `watch_note_taker_phone` capability. The watch sends to one reachable capability node, preferring a nearby node and then stable node ID ordering.

## Backend Contract

The phone sends an HTTPS `POST` with:

- `Authorization: Bearer <device-token>`
- `Content-Type: audio/mp4`
- `X-Note-Id: <stable UUID>`
- `X-Duration-Seconds: <1..120>`
- Raw MPEG-4/AAC audio body, at most 25 MiB

Responses:

- `200`: UTF-8 transcript body
- `400`, `413`, `415`: invalid audio
- `401`, `403`: authentication failure
- `402`: monthly or daily budget exceeded
- `429`, `5xx`: transient server failure

The backend must authenticate before processing, enforce a maximum duration of 120 seconds, accept at most 10 new stable note IDs per device per day, enforce a monthly minute ceiling below the provider free allowance, and use the stable note ID as its idempotency key. Provider credentials must remain backend-only. This repository contains the Android client contract, not a deployed transcription backend.

## Phone Setup

1. Open the phone app.
2. Enter the provisioned device token and select **Save device token**.
3. Select **Choose vault folder** and choose the Obsidian vault or destination folder.
4. Confirm the app reports `Vault: Connected`.

The picker stores the URI only after read/write permission is persisted and verified. The UI distinguishes connected, permission revoked, unavailable, and no folder selected. Use **Change vault folder** to replace it and **Retry Markdown delivery** after restoring access.

## Retry Behavior

- Watch recordings remain in app-private storage until a valid acknowledgement arrives from the exact phone node used for transfer.
- Transfer work is unique and uses persisted WorkManager exponential backoff after disconnection, write failure, or missing acknowledgement.
- The phone stores audio before acknowledgement and rejects truncated or oversized channel payloads.
- Transcription uploads one recording at a time. Network, rate-limit, and server failures retry with bounded exponential delay and at most eight automatic application attempts. Authentication, budget, and invalid-audio failures require user action.
- Markdown delivery uses persisted attempt IDs and WorkManager exponential backoff. Existing identical files are accepted; conflicting files are never overwritten.
- Watch queues, transcription state, review decisions, retry timestamps, and delivery attempts survive process death.

## Verify

JVM tests, Android unit tests, lint, and debug builds:

```powershell
.\gradlew.bat --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536m" test :phone:test :watch:test :phone:lintRelease :watch:lintRelease :phone:assembleDebug :watch:assembleDebug
```

Compile instrumentation tests:

```powershell
.\gradlew.bat :phone:compileDebugAndroidTestKotlin :watch:compileDebugAndroidTestKotlin
```

Run instrumentation tests on targeted connected devices:

```powershell
.\gradlew.bat :phone:connectedDebugAndroidTest
.\gradlew.bat :watch:connectedDebugAndroidTest
```

## Hardware Validation

Before release, verify on a physical paired phone/watch:

- Microphone denial/grant, manual stop, and automatic 120-second stop
- Ten-item queue limit and queue survival after watch process death
- Recording while disconnected, automatic reconnect transfer, and deletion only after acknowledgement
- Phone process death during transfer and durable duplicate receipt
- Backend success, authentication failure, budget failure, transient retry, and stable-ID idempotency
- Approve/reject confirmation and persisted status after restart
- Vault selection, delivery, identical retry, conflict refusal, revoked permission, reconnection, and exactly one Markdown file
- Same application ID, signing certificate, node discovery, channel listener, and acknowledgement listener

## Known Limitations

- Physical-device and real SAF-provider validation has not been completed in this workspace.
- A production backend deployment and provider integration are external to this repository.
- Instrumentation covers launch and persistence smoke paths; permission dialogs, external folder picker behavior, process killing, and paired Data Layer failures require device-level execution.
