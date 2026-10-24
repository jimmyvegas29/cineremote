@echo off
REM One-time install / update of the CineRemote virtual webcam (needs Administrator).
REM Copies the media source DLL where the Camera Frame Server service can load it and registers its
REM COM classes under HKLM. The DLL gets a versioned file name because Frame Server keeps the
REM previous one loaded; pass --restart to restart Frame Server so the new build loads now
REM (that briefly interrupts any webcam in use). uninstall_vcam.bat removes everything.
setlocal EnableDelayedExpansion
if not "%~1"=="--logged" ( call "%~f0" --logged %* > "%~dp0out\install.log" 2>&1 & exit /b !errorlevel! )
net session >nul 2>&1
if errorlevel 1 ( echo Please run as Administrator. & exit /b 1 )
set SRC=%~dp0out\cineremote-vcam.dll
set DEST=C:\Program Files\CineRemote
if not exist "%SRC%" ( echo Build first: vcam\build.bat & exit /b 1 )
mkdir "%DEST%" 2>nul
for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMddHHmmss"') do set STAMP=%%T
set DLL=%DEST%\cineremote-vcam-%STAMP%.dll
copy /y "%SRC%" "%DLL%" >nul || ( echo Copy failed & exit /b 1 )
copy /y "%~dp0out\cineremote-vcam-host.exe" "%DEST%\" >nul
REM Log folder: only LocalService (the Frame Server account) may write; everyone else reads.
mkdir "C:\ProgramData\CineRemote" 2>nul
icacls "C:\ProgramData\CineRemote" /remove:g *S-1-5-11 >nul 2>&1
icacls "C:\ProgramData\CineRemote" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F *S-1-5-19:(OI)(CI)M *S-1-5-32-545:(OI)(CI)RX >nul
regsvr32 /s "%DLL%" || ( echo regsvr32 failed & exit /b 1 )
if /i "%~2"=="--restart" (
  net stop FrameServerMonitor >nul 2>&1
  net stop FrameServer >nul 2>&1
  net start FrameServer >nul 2>&1
  net start FrameServerMonitor >nul 2>&1
  echo Frame Server restarted
)
for %%F in ("%DEST%\cineremote-vcam*.dll") do if /i not "%%~fF"=="%DLL%" del /q "%%~fF" 2>nul
echo Installed virtual camera: %DLL%
endlocal
