@echo off
chcp 65001 >nul
net session >nul 2>&1
if errorlevel 1 (
  echo Asking for administrator rights...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
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
python -m pip install --upgrade winrt-runtime winrt-Windows.Media.Control winrt-Windows.Storage.Streams winrt-Windows.Foundation uiautomation pycaw mss pillow aiortc soundcard numpy
for /f "delims=" %%i in ('python -c "import sys,os;print(os.path.join(os.path.dirname(sys.executable),'pythonw.exe'))"') do set PYW=%%i
echo Using %PYW%
del "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\PC Ultimate Controller.lnk" >nul 2>nul
echo Registering to start with Windows (with administrator rights)...
schtasks /Create /TN "PC Ultimate Controller" /TR "\"%PYW%\" \"%~dp0server.py\"" /SC ONLOGON /RL HIGHEST /F
echo Starting...
schtasks /Run /TN "PC Ultimate Controller"
echo.
echo Done. If Windows Firewall asks, allow Private AND Public networks.
timeout /t 5
