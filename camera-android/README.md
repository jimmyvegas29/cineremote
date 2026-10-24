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
- Recording to `Movies/CineRemote/*.mp4` on the phone (1080p30, locked frame rate), started/stopped on the
  phone or from CineRemote. Stream and recording stay upright with the phone either way round.
- **On-phone camera UI** laid out like the Blackmagic Camera app: LENS · FPS · SHUTTER · IRIS readouts,
  a running timecode (with frames) and ISO · WB · TINT across the top with blue "A" badges for automatic
  values and a resolution/aspect badge; live RGB histogram, record-time/storage and stereo audio meter
  panels along the bottom; a tool column (frame guides, autofocus, exposure, torch, record, lens/zoom,
  stabilisation, front/back) and a Camera · Media · Remote · Settings rail. Tap a readout or tool to edit
  it on a value ruler (with AUTO); tap the picture to focus.

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
| `Recorder.kt` | on-phone MP4 recording |
| `ControllerSession.kt` | binds one controller's signaling + data channel + camera |
| `FrameTap.kt` | the camera's GPU frame tap: turns frames upright, feeds the preview, stream and histogram |
| `AudioMeter.kt` | input levels for the audio meter |
| `CameraHud.kt` | the on-phone camera screen |
| `MainActivity.kt` | wiring, preview placement, settings / media / remote |

The UI font is [Lato](https://www.latofonts.com/) (SIL Open Font License, see `LATO_OFL.txt`).
