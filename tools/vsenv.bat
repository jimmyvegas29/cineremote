@echo off
REM Put the MSVC x64 build tools on PATH (no-op inside a Developer Command Prompt or CI with them set up).
REM Finds Visual Studio 2022 / Build Tools with the C++ workload via vswhere. Usage: call tools\vsenv.bat
if defined VSCMD_VER exit /b 0
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" ( echo [build] Visual Studio installer not found - install VS 2022 Build Tools with C++ & exit /b 1 )
set VSDIR=
for /f "usebackq delims=" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSDIR=%%I"
if not defined VSDIR ( echo [build] Visual Studio C++ build tools not found & exit /b 1 )
call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 ( echo [build] vcvars64 failed & exit /b 1 )
exit /b 0
