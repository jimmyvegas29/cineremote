@echo off
REM Build the virtual webcam media source DLL and its host exe with MSVC.
setlocal
set ROOT=%~dp0
set OUT=%ROOT%out
if not exist "%OUT%" mkdir "%OUT%"
call "%~dp0..\tools\vsenv.bat" || exit /b 1

set DEFS=/DUNICODE /D_UNICODE /DWINVER=0x0A00 /D_WIN32_WINNT=0x0A00 /DNTDDI_VERSION=0x0A00000C
set CFLAGS=/nologo /O2 /MT /EHsc /std:c++17 /W3 %DEFS%

echo [build] media source DLL
cl %CFLAGS% /LD "%ROOT%src\vcam_source.cpp" /Fo"%OUT%\vcam_source.obj" /Fe"%OUT%\cineremote-vcam.dll" ^
   /link /DEF:"%ROOT%src\vcam_source.def" mfplat.lib mfuuid.lib mf.lib ole32.lib advapi32.lib
if errorlevel 1 ( echo [build] DLL failed & exit /b 1 )

echo [build] host exe
cl %CFLAGS% "%ROOT%src\vcam_host.cpp" /Fo"%OUT%\vcam_host.obj" /Fe"%OUT%\cineremote-vcam-host.exe" ^
   /link mfplat.lib mfsensorgroup.lib ole32.lib
if errorlevel 1 ( echo [build] host failed & exit /b 1 )

echo [build] OK
endlocal
