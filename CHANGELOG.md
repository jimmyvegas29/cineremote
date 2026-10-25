# Changelog

## 1.1.7

- **Audio meters show real stereo.** CineRemote now asks the phone for stereo audio. Without that, some
  phones sent mono and the left and right meters (and the audio in OBS) were always identical. Blackmagic
  Camera already sent stereo; the CineRemote Camera Android app now streams stereo too.
- **More room for the picture.** Click a setting tile again to close its ruler: the controls shrink and the
  video gets bigger. CineRemote remembers which tile was open.
- **Lens and frame rate are buttons** instead of a ruler, since they only have a few fixed values. The ISO and
  shutter rulers only show marks for values you can actually pick, and clicking a value jumps to it.
- **Choose which cameras are in the multiview** with the new grid icon on each camera card.
- **Multiview:** double-click a camera's picture to set its focus point; the icon in its corner opens that
  camera on its own.
- **Camera cards:** the record-arm ring is now green (armed is not recording), and the camera name has the
  phone's name under it.

## 1.1.6

- **Tap to focus now focuses where you click.** With the phone in landscape (either way up), clicking the
  picture to set focus/exposure put the point a quarter-turn away from where you clicked - only the centre
  of the frame worked. Fixed for Blackmagic Camera on both Android and iPhone (checked on a Pixel 10 and an
  iPhone 16); portrait is unchanged.
- New in the repository: **CineRemote Camera**, an Android app that turns phones without Blackmagic Camera
  support into cameras CineRemote can find and control (`camera-android/`, build it from source).

## 1.1.5

- **macOS:** the app is now signed with a stable CineRemote certificate instead of a throwaway signature that
  changed with every build. macOS then recognises each update as the same app, so permissions you granted -
  above all **Local Network** (needed to find the phones) - should carry over to future updates instead of
  being asked for again. You may have to allow Local Network one last time for 1.1.5. (The first-launch
  "could not verify" prompt still needs *Open Anyway*: that one only goes away with Apple notarization.)

## 1.1.4

- **macOS:** releases ship only the `.dmg` now (no separate `.zip`), and the in-app updater installs from it.
  **On 1.1.3 or earlier, download this `.dmg` by hand once**; updates are automatic from here on.
- **macOS:** fixed updates that said they worked but left the old version running: when CineRemote is opened
  straight from Downloads, macOS runs it from a temporary read-only copy, so the swap failed silently. The
  updater now finds the real install location, tells you up front if it can't replace the app (and to move it
  to Applications), and reports a failed swap on the next start instead of hiding it.

## 1.1.3

- Fixed the recording timecode. It stayed at 00:00:00:00 while recording (Pixel) or showed a huge number
  like 498004:25:04:16 (iPhone): the phones send the wall-clock time a recording **started**, not a running
  count. CineRemote now counts the take itself from that start (falling back to when it saw recording
  begin), with the frames field ticking, and shows the last clip's length when idle.

## 1.1.2

- **macOS disk image:** each release now has a `.dmg` for Apple Silicon and for Intel - open it and drag
  CineRemote onto Applications. The `.zip` files stay (the in-app updater uses them).
- README: current macOS first-launch steps (*System Settings ▸ Privacy & Security ▸ Open Anyway*), since
  right-click ▸ Open no longer bypasses the check on recent macOS.

## 1.1.1

- Fixed: the update check could fail to verify github.com's certificate - always on macOS (the app had no
  root certificates) and on Windows PCs that hadn't cached the needed root certificate yet. The app now
  carries its own CA bundle. **On 1.0.0 or 1.1.0 and not seeing "Update available"? Download this release
  once by hand**; updates are automatic from here on. Linux was not affected.
- Windows updates are more robust: the installer now starts only after CineRemote has fully closed
  (including its clean disconnects from the phones), instead of racing the closing app.

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
