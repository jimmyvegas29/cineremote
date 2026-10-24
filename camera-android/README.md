# CineRemote Camera (Android)

A companion camera app: turns an Android phone into a camera that
**[CineRemote](../README.md)** can discover and control, for phones that can't run the Blackmagic
Camera app. It speaks the same remote-camera protocol CineRemote uses, so the desktop app treats it
like any other camera — live preview, manual controls, recording.

> Independent project, not affiliated with Blackmagic Design. It is a clean-room camera app that
> implements CineRemote's own control protocol; it contains no Blackmagic code.

## What works

- Automatic discovery on the LAN (mDNS `_bmd-cam-control._tcp`), password sign-in.
- Live **1080p H.264** video + audio to CineRemote over WebRTC.
- Manual control from CineRemote: lens, frame rate, ISO, shutter, white balance, tint, exposure
  compensation, focus (and tap-to-focus), zoom, stabilization, torch.
- Recording to `Movies/CineRemote/*.mp4` on the phone, started/stopped on the phone (record button or a
  volume key) or from CineRemote. **Resolution** Open Gate / 4K / 3K / 2K / HD with the **aspect ratios**
  each offers (every size the lens and the hardware encoder can record, e.g. UHD 3840x2160, 4:3 open gate,
  1:1, 2.22:1), **codec** H.264 or HEVC, and Low/Medium/High/Max **bit rate**. Locked frame rate; stream
  and recordings stay upright with the phone either way round. The live stream stays at up to 1080p.
- **Audio**: stereo AAC with an **Audio Gain** control (applied to the recording, the meters and the stream).
- **On-phone camera UI** laid out like the Blackmagic Camera app: LENS · FPS · SHUTTER · IRIS readouts,
  a running timecode (with frames), ISO · WB · TINT with blue "A" badges, and a resolution/aspect badge
  (tap it for Resolution); live RGB histogram (tap for an RGB parade), record-time/storage and audio meter
  (tap for big meters + gain) panels; a tool column (guides, autofocus, exposure, record, lens/zoom,
  stabilisation, front/back) and a Camera · Media · Remote · Settings rail.
- Editors like the Blackmagic app's: rulers with AUTO, ISO preset buttons, white balance presets with a
  Kelvin ruler and lock, shutter (auto exposure) lock, a lens row with the zoom ruler, and a guides
  toolbar (torch, safe area, frame guides 2.76:1 … 1:1, grids).
- **Settings**: Record (codec, bit rate, resolution/aspect), Camera (lock WB on record, volume-key record,
  stabilisation), Audio (gain, stereo/mono), Monitor (guide/grid opacity, which panels show), Remote
  Camera Control (on/off, name, letter, password).

Not done yet (Blackmagic features with no equivalent here): LUTs, zebras / focus assist / false colour,
presets, slate metadata, timelapse and off-speed, function buttons, live streaming, chat, proxies, cloud.

Verified on a Samsung Galaxy S20 FE (Android 13) against CineRemote on Windows.

## Build

Needs the Android SDK (platform 35, build-tools 35) and a JDK 17+. From this folder:

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # to a connected phone (adb)
```

The protobuf control schema is shared with the desktop app (`../cineremote/proto/*.proto`) and
compiled into the APK by the Gradle protobuf plugin.

## Use

1. Install and open the app; grant Camera and Microphone.
2. In **Settings**, set the camera name, letter and a password (default `cineremote`).
3. On the same network, open CineRemote; the phone appears in the camera list. Connect with the
   password.

## Layout

| File | Role |
|---|---|
| `RemoteServer.kt` | mDNS advertisement + WebSocket sign-in (salted SHA-256), one controller at a time |
| `RtcLink.kt` | WebRTC: data channel + sendonly H.264/Opus, non-trickle ICE |
| `CameraController.kt` | the protobuf control protocol: properties in/out, preview, recording |
| `CameraEngine.kt` | Camera2 pipeline and every manual control |
| `Recorder.kt` | on-phone recording: hardware H.264/HEVC + AAC, muxed to MP4 |
| `Takes.kt` | starts/stops takes (record button, volume key, CineRemote) |
| `Formats.kt` | which resolutions/aspect ratios a lens + encoder can record, bit rates |
| `ControllerSession.kt` | binds one controller's signaling + data channel + camera |
| `FrameTap.kt` | the camera's GPU frame tap: turns frames upright, feeds the preview, stream and histogram |
| `AudioInput.kt` | the mic: gain, meter levels, PCM for the recorder |
| `CameraHud.kt` | the on-phone camera screen |
| `SettingsPanel.kt` | the Settings screen |
| `MainActivity.kt` | wiring, preview placement, media / remote |

The UI font is [Lato](https://www.latofonts.com/) (SIL Open Font License, see `LATO_OFL.txt`).
