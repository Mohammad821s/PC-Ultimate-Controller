@echo off
cd /d "%~dp0"
call restart.bat
timeout /t 6 /nobreak >nul
python test_proto.py
