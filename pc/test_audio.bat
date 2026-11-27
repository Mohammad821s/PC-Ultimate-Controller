@echo off
cd /d "%~dp0"
python -m pip install -q soundcard numpy
call restart.bat
timeout /t 10 /nobreak >nul
python test_audio.py
