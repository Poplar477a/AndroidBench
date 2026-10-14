@echo off
chcp 65001 >nul
title QPM Login (password passed as argument)
echo ============================================================
echo   QPM Login - ALTERNATIVE, password typed via cmd
echo ============================================================
echo.
echo   NOTE: with this method the password is visible while you
echo   type it, and it appears in this console window's command
echo   line. Prefer qpm-shell.cmd unless that one fails.
echo.
set /p QPMUSER=Qualcomm account (email): 
set /p QPMPASS=Password (will be visible): 
echo.
wsl -d Ubuntu-26.04 -u dsh -- /mnt/d/Beta/.tools/qpm.sh --login %QPMUSER% %QPMPASS%
echo.
pause
