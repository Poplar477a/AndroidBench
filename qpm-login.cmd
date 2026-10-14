@echo off
chcp 65001 >nul
title QPM Login - Qualcomm Package Manager
echo ============================================================
echo   Qualcomm Package Manager - Login
echo ============================================================
echo.
echo   Step 1: type your Qualcomm developer account (email).
echo   Step 2: QPM will prompt 'Password:' - type it there.
echo.
echo   Your password is typed into QPM's own prompt: it is not
echo   echoed, not stored in this script, and not put on the
echo   command line. It goes only to your local WSL session file.
echo.
echo ------------------------------------------------------------
set /p QPMUSER=Qualcomm account (email): 
if "%QPMUSER%"=="" (
  echo.
  echo   No account entered. Aborting.
  pause
  exit /b 1
)
echo.
echo   Now enter the password at the prompt below.
echo ------------------------------------------------------------
wsl -d Ubuntu-26.04 -u dsh -- /mnt/d/Beta/.tools/qpm.sh --login %QPMUSER%
echo ------------------------------------------------------------
echo.
echo   If it says 'Login successful', tell the assistant to continue.
echo.
pause
