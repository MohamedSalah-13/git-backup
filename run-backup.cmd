@echo off
rem Double-click to back up now. Extra arguments are passed on, e.g.  run-backup.cmd -DryRun
powershell -NoProfile -File "%~dp0backup-repos.ps1" %*
echo.
pause
