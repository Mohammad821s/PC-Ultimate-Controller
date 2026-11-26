@echo off
schtasks /End /TN "PC Ultimate Controller"
timeout /t 2 >nul
schtasks /Run /TN "PC Ultimate Controller"
timeout /t 3
