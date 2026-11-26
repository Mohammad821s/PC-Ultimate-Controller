@echo off
cd /d "%~dp0"
python -m pip install -q uiautomation
python test_tray.py
