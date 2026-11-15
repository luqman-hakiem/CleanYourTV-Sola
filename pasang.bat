@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo  Pasang CleanYourTV Sola ke Android TV
echo ============================================
echo.

set /p IP=IP TV (contoh 192.168.1.20):
if "%IP%"=="" goto :end

set /p FRESH=TV sudah pernah pakai CleanYourTV versi resmi? Hapus dulu? (y/N):
echo.

set /p REBOOT=Reboot TV setelah pasang, lalu periksa Home otomatis? (Y/n):
echo.

set "EXTRA="
if /i "%FRESH%"=="y" set "EXTRA=-Fresh"

set "NOREB="
if /i "%REBOOT%"=="n" set "NOREB=-NoReboot"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-adb.ps1" -Device %IP% %EXTRA% %NOREB%

:end
echo.
pause
