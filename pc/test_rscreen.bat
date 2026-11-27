@echo off
cd /d "%~dp0"
call restart.bat
timeout /t 10 /nobreak >nul
python test_rscreen.py
