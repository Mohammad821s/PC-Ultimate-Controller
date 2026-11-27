@echo off
cd /d "%~dp0"
"%ProgramFiles%\Windows Defender\MpCmdRun.exe" -SignatureUpdate > defender_update.log 2>&1
call check_defender.bat
