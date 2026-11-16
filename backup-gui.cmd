@echo off
rem Double-click to open the window for choosing repositories and running the backup.
start "" powershell -NoProfile -STA -WindowStyle Hidden -File "%~dp0backup-gui.ps1"
