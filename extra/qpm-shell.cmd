@echo off
chcp 65001 >nul
title QPM - WSL interactive shell
echo ============================================================
echo   QPM Login (interactive WSL shell)
echo ============================================================
echo.
echo   A real Linux shell will open below. Paste this line:
echo.
echo       /mnt/d/Beta/.tools/qpm.sh --login YOUR_EMAIL
echo.
echo   It will then prompt 'Password:' and read it properly.
echo   Type 'exit' to leave the shell when done.
echo.
echo ============================================================
echo.
wsl -d Ubuntu-26.04 -u dsh
