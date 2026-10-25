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
  toolbar (torch, focus assist, safe area, frame guides 2.76:1 … 1:1, grids, zebra).
- **Monitoring** (drawn by the preview's shader, so on the phone screen only, never in recordings or the
  stream): display **LUTs** (built-in looks plus imported .cube files, picked from live thumbnails), **false
  colour**, **focus assist** (coloured lines or Peak glow) and **zebras** (Mid Grey, MG+1, 75–100%).
- **Face detection**: boxes on faces; with AF on AUTO, focus and exposure follow the largest face.
- **Off-speed** (slow motion): the sensor runs faster than the project rate, e.g. 120 fps played at 30.
  **Timelapse**: one frame every 1 s … 10 min. Both from the FPS panel, both reported to CineRemote.
- **Presets** (left-edge dots): save, apply, update, rename, delete whole camera setups.
- **Slate** (left-edge dots or Settings): reel, scene, take (auto-increment), INT/EXT, DAY/NIGHT, good
  take, production, director, operator - shared with CineRemote's slate editor. Clips are named like a
  Blackmagic camera's (`C001_10242051_C005.mp4`) with the slate in the clip's description.
- **Function buttons** F1–F3 (right-edge dots), each assignable to one of 16 actions; long-press to change.
- **Settings**: Record (codec, bit rate, resolution/aspect), Camera (lock WB on record, volume-key record,
  stabilisation), Audio (gain, stereo/mono), Monitor (focus assist style and colour, zebra level, guide/grid
  opacity, which panels show), Remote
  Camera Control (on/off, name, letter, password).

Not planned: live streaming, chat, proxies, Blackmagic Cloud.

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
| `MonitorAids.kt` | LUT, false colour, focus assist, zebras: the preview renderer's GL drawer and shader |
| `Luts.kt` | built-in looks, .cube parsing/import |
| `Presets.kt` / `Slate.kt` | saved setups; the clip slate, file names and descriptions |
| `SettingsPanel.kt` | the Settings screen |
| `MainActivity.kt` | wiring, preview placement, media / remote |

Phone quirks found on the S20 FE (Snapdragon 865), which shaped the design:
- Three camera outputs at 1080p30 drop to ~26 fps, so the camera feeds only the frame tap and the encoder;
  the preview and stream are drawn from GPU copies of the tap's frames.
- The camera service re-stamps frames for a video encoder on the monotonic clock even when the camera
  reports boot time; recorded audio is stamped on the monotonic clock to match.
- Samsung's HAL can't build its preview+video high-frame-rate pipeline (the autofocus stage fails), so a
  120 fps take records the encoder alone and the picture pauses for the take. 240 fps is advertised but
  delivers black frames; the app detects that and stops offering the rate.

The UI font is [Lato](https://www.latofonts.com/) (SIL Open Font License, see `LATO_OFL.txt`).
