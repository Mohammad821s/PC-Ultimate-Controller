@echo off
cd /d "%~dp0"
if exist server.pid (
  for /f %%p in (server.pid) do taskkill /PID %%p /F >nul 2>nul
  del server.pid
)
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*server.py*' -and $_.Name -like 'python*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
echo Stopped.
