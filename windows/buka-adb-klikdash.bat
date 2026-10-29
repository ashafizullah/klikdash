@echo off
setlocal EnableExtensions DisableDelayedExpansion
title KlikDash - Buka ADB headunit
rem Membuka port ADB 5555 di headunit Android 10 ke bawah (tanpa Wireless debugging), supaya KlikDash bisa memakai
rem Hemat Daya, tampilan utama, dan penyetelan otomatis. Pengguna cukup menyambungkan kabel USB dan mengizinkan
rem "Allow USB debugging?" di headunit. ADB resmi Google (platform-tools) diunduh sekali ke %LOCALAPPDATA%\KlikDash.
rem Butuh Windows 10 versi 1803 atau lebih baru (curl dan tar bawaan Windows). File ini harus ASCII dengan akhir baris CRLF.

set "ROOT=%LOCALAPPDATA%\KlikDash"
set "ADB=%ROOT%\platform-tools\adb.exe"
set "ZIP=%ROOT%\platform-tools.zip"
set "URL=https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

echo.
echo   KLIKDASH - BUKA ADB HEADUNIT
echo   ============================
echo.

if exist "%ADB%" goto ada_adb
echo   [1/3] Mengunduh ADB resmi dari Google...
if not exist "%ROOT%" mkdir "%ROOT%"
curl -L --fail --silent --show-error -o "%ZIP%" "%URL%"
if errorlevel 1 goto gagal_unduh
tar -xf "%ZIP%" -C "%ROOT%"
if errorlevel 1 goto gagal_unduh
del "%ZIP%" >nul 2>&1
if not exist "%ADB%" goto gagal_unduh
:ada_adb
echo   [1/3] ADB siap.
echo.

call "%ADB%" start-server >nul 2>&1
echo   [2/3] Sambungkan headunit ke laptop dengan kabel USB.
echo         Menunggu headunit...
set "N=0"
set "ASKED="
:tunggu
rem get-state berhasil hanya bila headunit tersambung lewat USB dan sudah mengizinkan laptop ini.
call "%ADB%" -d get-state >nul 2>&1
if not errorlevel 1 goto tersambung
if defined ASKED goto tunggu_lagi
call "%ADB%" devices 2>nul | find "unauthorized" >nul
if errorlevel 1 goto tunggu_lagi
echo.
echo         Di layar headunit muncul "Allow USB debugging?":
echo         centang "Always allow from this computer", lalu ketuk Allow.
set "ASKED=1"
:tunggu_lagi
set /a N+=1
if "%N%"=="30" call :tips
ping -n 3 127.0.0.1 >nul
goto tunggu

:tersambung
echo.
echo   [2/3] Headunit tersambung.
echo.
echo   [3/3] Membuka port ADB 5555...
rem Supaya tetap terbuka setelah headunit dinyalakan ulang. Tidak semua firmware mengizinkannya: diam-diam dilewati.
set "TETAP="
call "%ADB%" -d shell setprop persist.adb.tcp.port 5555 >nul 2>&1
call "%ADB%" -d shell getprop persist.adb.tcp.port 2>nul | find "5555" >nul
if not errorlevel 1 set "TETAP=1"
call "%ADB%" -d tcpip 5555 >nul 2>&1
if errorlevel 1 goto gagal_buka
echo.
echo   ==========================================
echo   BERHASIL. Port ADB 5555 sudah terbuka.
echo   ==========================================
echo.
echo   Cabut kabelnya, lalu buka KlikDash: Pengaturan, tab Hemat Daya.
if defined TETAP echo   Port tetap terbuka walau headunit dinyalakan ulang.
if not defined TETAP echo   Bila headunit dinyalakan ulang total, jalankan file ini lagi.
goto selesai

:gagal_unduh
echo.
echo   GAGAL mengunduh ADB. Pastikan laptop tersambung ke internet, lalu jalankan file ini lagi.
echo   Butuh Windows 10 atau lebih baru.
goto selesai

:gagal_buka
echo.
echo   GAGAL membuka port. Cabut lalu sambungkan lagi kabelnya, kemudian jalankan file ini lagi.
goto selesai

:tips
echo.
echo         Headunit belum terdeteksi. Coba:
echo         - Kabel USB yang bisa transfer data, bukan kabel cas saja.
echo         - Port USB lain di headunit atau di laptop.
echo         - Pastikan USB debugging di headunit sudah aktif.
echo         Tetap menunggu... Tutup jendela ini untuk berhenti.
exit /b

:selesai
call "%ADB%" kill-server >nul 2>&1
echo.
echo   Tekan tombol apa saja untuk menutup.
pause >nul
endlocal
