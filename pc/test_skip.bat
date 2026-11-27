@echo off
cd /d "%~dp0"
python test_skip.py > test_skip.out 2>&1
