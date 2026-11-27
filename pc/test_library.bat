@echo off
cd /d "%~dp0"
python -m pip install -q pycaw
python test_library.py
