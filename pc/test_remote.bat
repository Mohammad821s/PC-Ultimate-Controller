@echo off
cd /d "%~dp0"
python -m pip install -q aiortc
call restart.bat
timeout /t 10 /nobreak >nul
python test_remote.py
