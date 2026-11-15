@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo  Pasang suara adzan ke Android TV
echo ============================================
echo.
echo  Pasang dulu APK-nya (pasang.bat) sebelum menjalankan ini.
echo.

set /p IP=IP TV (contoh 192.168.1.20):
if "%IP%"=="" goto :end

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pasang-adzan.ps1" -Device %IP%

:end
echo.
pause
