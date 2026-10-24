@echo off
REM Remove the CineRemote virtual webcam (needs Administrator).
net session >nul 2>&1
if errorlevel 1 ( echo Please run as Administrator. & exit /b 1 )
set DEST=C:\Program Files\CineRemote
REM The webcams are persistent per-user registrations: unregister them before the DLL goes away.
pushd "%~dp0.." && ( python -m cineremote.vcam --remove-all 2>nul & popd )
for %%F in ("%DEST%\cineremote-vcam*.dll") do regsvr32 /s /u "%%~fF"
for %%F in ("%DEST%\cineremote-vcam*.dll") do del /q "%%~fF" 2>nul
del /q "%DEST%\cineremote-vcam-host.exe" 2>nul
rmdir /s /q "C:\ProgramData\CineRemote" 2>nul
echo Virtual camera removed (files still in use are deleted after reboot or next install).
