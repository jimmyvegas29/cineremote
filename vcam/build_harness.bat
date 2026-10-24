@echo off
call "%~dp0..\tools\vsenv.bat" || exit /b 1
cl /nologo /O2 /MT /EHsc /std:c++17 /DUNICODE /D_UNICODE "%~dp0test\source_harness.cpp" /Fo"%~dp0out\source_harness.obj" /Fe"%~dp0out\source_harness.exe" /link mfplat.lib mfuuid.lib ole32.lib >nul
if errorlevel 1 ( echo COMPILE_FAILED & exit /b 1 )
echo COMPILE_OK
