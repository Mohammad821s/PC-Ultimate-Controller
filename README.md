# PC Ultimate Controller

Control your Windows PC from your Android phone, over your home network.

- Touchpad (one finger moves, tap = left click, two-finger tap = right click, two fingers scroll)
- Floating touchpad that stays on top of any app
- Home-screen widget: YouTube, Netflix, Crunchyroll, Fortnite, media buttons, floating touchpad
- Media notification with what is playing on the PC (shows in the Now Bar / quick settings)
- Keyboard: type on the phone, send to the PC

## Download

- Phone app: [PC-Ultimate-Controller.apk](../../releases/latest/download/PC-Ultimate-Controller.apk)
- PC server: [PC-Server.zip](../../releases/latest/download/PC-Server.zip)

Every push to `main` builds a new release automatically.

## Setup

**PC (Windows 10/11, Python 3.9+)**
1. Download `PC-Server.zip` and extract it somewhere permanent (e.g. `Documents\PC-Ultimate-Controller`).
2. Run `setup.bat`. It installs what it needs, adds itself to Windows startup and starts the server.
3. A window shows the PC address and the PIN. If Windows Firewall asks, allow **Private and Public** networks.

**Phone**
1. Open the APK link above in Chrome and install it (allow Chrome to install unknown apps the first time).
2. Open the app, tap **Search automatically**, enter the PIN, save.
3. Long-press the home screen → Widgets → PC Ultimate Controller.

## Customize (pc/config.json)

- `chrome_profile`: Chrome profile name to open YouTube/Netflix in (default `Song`).
- `shortcuts`: each shortcut is one of
  - `{"type": "chrome", "url": "..."}` opens a page in Chrome
  - `{"type": "app", "name": "..."}` opens an installed Store app by name
  - `{"type": "uri", "uri": "..."}` opens a link such as an Epic Games launcher link
  - `{"type": "command", "command": "..."}` runs a command

Restart the server (`stop.bat`, then `setup.bat` or `start.bat`) after editing.

## Notes

- Add the signing key as the repository secret `KEYSTORE_B64` (base64 of a PKCS12 keystore, alias `pcultimate`, password `pcultimate`) so every build can update the installed app. Without it each build uses a temporary key and needs a reinstall.
- Phone and PC must be on the same network.
