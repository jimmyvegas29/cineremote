# CineRemote - working notes for Claude

Desktop remote control / monitor / OBS + webcam output for the Blackmagic Camera app (iOS, Android).
Python 3.12 + PySide6 app, a C OBS plugin, a C++ Windows virtual webcam. Public repo
`jimmyvegas29/cineremote`, MIT. Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) before changing the
protocol, outputs, packaging or updater; [`CHANGELOG.md`](CHANGELOG.md) says what shipped when.

## Run and check

```bash
pip install -r requirements.txt
python cineremote.py                      # real phones (mDNS)
python cineremote.py --demo               # 5 simulated cameras, no phone needed
QT_QPA_PLATFORM=offscreen python cineremote.py --demo --quit-after 6   # smoke test: must exit 0
python tools/obs_record_test.py --demo    # OBS end-to-end through the plugin (OBS installed)
python cineremote.py --update-now         # updater against the live GitHub release
```

Screenshots for the README come from demo mode, never from a real phone:
`python cineremote.py --demo --view studio|camera [--multiview] --select N --tools histogram,guides --screenshot docs/images/x.png --screenshot-after 7 --quit-after 9`
(then `python tools/make_banner.py` if `multiview.png` changed). Art: `tools/make_icons.py` (from
`assets/icon.svg`), `tools/make_banner.py`.

Native parts: `obs-plugin\build.bat` / `build_linux.sh` / `OBS_UNIVERSAL=1 build_macos.sh`;
`vcam\build.bat`. Windows builds find MSVC through `tools\vsenv.bat`.

## Invariants (breaking these breaks users)

- **Protocol:** never send the first WebRTC offer; `StartPreviewRequest` needs `frame_rate`; always
  disconnect cleanly (DisconnectEvent → data channel → peer connection) or the phone wedges until its app
  restarts; one controller per phone. iOS doesn't echo writes - `session.write()` re-reads.
- **Proto schema:** edit `cineremote/proto/*.proto`, then `python tools/gen_proto.py` (needs
  `grpcio-tools`) and commit the regenerated `schema.pb`. Field numbers must match the app.
- **OBS shared memory:** `obs_shm.py` layout and `obs-plugin/src/cineremote-shm.h` must match byte for
  byte; plugin source id `cineremote_camera` (changing it breaks users' OBS scenes).
- **Updater contract:** release asset names (`CineRemote-<v>-windows-x64-setup.exe`,
  `-macos-arm64.dmg`, `-macos-x86_64.dmg`, `-linux-x86_64.AppImage`, `SHA256SUMS.txt`) are matched by
  `updater.asset_pattern()`. Rename one side only together with the other, and remember old installs
  still look for the old names.
- **Outputs stay clean:** monitoring tools and overlays draw only in the app's views, never into frames
  sent to OBS or the webcam.
- **Threads:** backend → GUI only via Qt signals; GUI → backend only via `Backend.call()` / connect /
  disconnect. Each frame consumer owns its own `VideoReformatter`.
- **macOS signing certificate** (repo secrets `MACOS_SIGN_P12*`) must stay the same: replacing it changes
  the app's identity and every Mac user has to re-allow Local Network once.
- **Python 3.12+** (PySide6 6.12 Linux wheels crash on 3.11). Intel-Mac `cryptography<49` pin stays
  until a newer release ships an x86_64 macOS wheel.
- No personal data in the repo: no phone IPs, passwords, serials or real-phone screenshots. Tools take
  `--host` / `--password` arguments.

## Releasing

1. Bump `__version__` in `cineremote/__init__.py`.
2. Add a `## x.y.z` section at the top of `CHANGELOG.md` - it becomes the GitHub release notes **and**
   the text of the in-app update dialog.
3. Update README / screenshots / `docs/ARCHITECTURE.md` if behaviour changed.
4. Smoke test (`--demo --quit-after`), commit, then `git tag -a vx.y.z -m "CineRemote x.y.z"` and
   `git push origin main vx.y.z`.
5. `.github/workflows/build.yml` builds Windows, Linux, macOS arm64 + Intel, smoke-tests each, and the
   `release` job publishes all files + `SHA256SUMS.txt`. The tag must equal `__version__` or the release
   job fails. Watch with `gh run watch <id> -R jimmyvegas29/cineremote --exit-status`.
6. Check `gh release view vx.y.z -R jimmyvegas29/cineremote` lists 8 assets, then test the update from
   the previous version (`CineRemote --update-now`, or the Update available button).

Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (or the current model).

## Tooling notes

- The Bash tool collapses `\\` even inside quoted heredocs: write files containing Windows paths or
  backslash escapes with the Write/Edit tools, not `python - <<EOF` patches.
- `.gitattributes` keeps LF everywhere and CRLF for `.bat`/`.iss`/`.ini`; shell scripts are committed
  executable (`git update-index --chmod=+x`).
