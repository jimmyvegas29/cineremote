# Changelog

## 1.1.0

- **Record-arm:** a red ring on each camera card arms that camera. With any camera armed, the top button
  becomes **Record armed (n)** and rolls only the armed cameras; with none armed it stays **Record all**.
  Arm state is remembered per camera. **Stop all** still stops every camera.
- **Audio meters** show their zones all the time, like the phone app's: green, **orange from -12 dBFS** and
  **red from -6 dBFS** (unlit segments are tinted, peak markers take their zone's colour). The multiview
  tiles' mini meters are zoned the same way.
- The camera card's preview switch lost its "LIVE" caption (it clashed with the connection status chip);
  hover it for what it does.
- `--update-now` installs the latest release from the command line (for scripted installs and testing).
- Running from source on Linux now requires Python 3.12+ (PySide6's Linux build crashes on 3.11).

## 1.0.0

First public release.

- Remote control and live monitoring for the Blackmagic Camera app (iPhone, iPad, Android) from Windows,
  macOS and Linux: automatic discovery, low-latency H.265 preview with audio, full camera control,
  recording on one phone or all of them.
- Studio view (multi-camera cards, multiview, monitoring tools) and Camera view (the phone app's layout).
- Native OBS Studio source with video and audio, one source per phone.
- Virtual webcam on Windows (Media Foundation) and Linux (v4l2loopback).
- Demo mode (`--demo`) with simulated cameras.
- In-app updates from GitHub Releases, verified with SHA-256 checksums.
