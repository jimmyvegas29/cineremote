@echo off
REM Install the CineRemote Camera OBS plugin for the current user (no admin needed).
set SRC=%~dp0out\cineremote-obs.dll
set DEST=C:\ProgramData\obs-studio\plugins\cineremote-obs
if not exist "%SRC%" ( echo Build first: run build.bat & exit /b 1 )
mkdir "%DEST%\bin\64bit" 2>nul
mkdir "%DEST%\data\locale" 2>nul
copy /y "%SRC%" "%DEST%\bin\64bit\" >nul
> "%DEST%\data\locale\en-US.ini" echo CineRemote Camera="CineRemote Camera"
echo Installed to %DEST%
echo Restart OBS, then Add Source -^> CineRemote Camera.
