@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Installing PC Ultimate Controller...
python -m pip install --upgrade websockets
if errorlevel 1 (
  echo.
  echo Python was not found. Install Python from python.org and tick "Add python.exe to PATH".
  pause
  exit /b 1
)
echo Installing now-playing support (optional)...
python -m pip install --upgrade winrt-runtime winrt-Windows.Media.Control winrt-Windows.Storage.Streams winrt-Windows.Foundation
set PYW=pythonw
where pythonw >nul 2>nul || set PYW=pyw
echo Adding to Windows startup...
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Startup')+'\PC Ultimate Controller.lnk'); $s.TargetPath=(Get-Command %PYW%).Source; $s.Arguments='\"%~dp0server.py\"'; $s.WorkingDirectory='%~dp0'; $s.Save()"
echo Starting...
start "" %PYW% "%~dp0server.py"
echo.
echo Done. A window will show your PIN. If Windows Firewall asks, allow Private AND Public networks.
pause
