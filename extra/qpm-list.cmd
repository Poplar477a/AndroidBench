@echo off
chcp 65001 >nul
title QPM - Product List
echo ============================================================
echo   QPM downloadable products (login required first)
echo ============================================================
echo.
wsl -d Ubuntu-26.04 -u dsh -- /mnt/d/Beta/.tools/qpm.sh --product-list
echo.
pause
