@echo off
call "%~dp0..\tools\vsenv.bat" || exit /b 1
cl /nologo /O2 /MT /EHsc /std:c++17 /DUNICODE /D_UNICODE "%~dp0test\vcam_test.cpp" /Fo"%~dp0out\vcam_test.obj" /Fe"%~dp0out\vcam_test.exe" /link mfplat.lib mf.lib mfreadwrite.lib mfuuid.lib ole32.lib >nul
if errorlevel 1 ( echo COMPILE_FAILED & exit /b 1 )
echo COMPILE_OK
