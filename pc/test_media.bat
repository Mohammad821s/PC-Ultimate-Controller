@echo off
cd /d "%~dp0"
tasklist /fi "imagename eq python.exe" > test_media.out 2>&1
python -X faulthandler test_media.py skip >> test_media.out 2>&1
