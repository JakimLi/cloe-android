# Cloe Android

Android floating window client for Cloe. Connects to the PC-side Bridge via Tailscale networking to display Cloe's GIF animations remotely.

## Architecture

```
Hermes Agent (PC) → Bridge (PC:19851) → Tailscale → Android App (floating window)
```

- GIFs are bundled inside the APK (`assets/gifs/`) — no network loading needed
- WebSocket only transmits action commands (a few dozen bytes of JSON)
- Tailscale split tunnel keeps normal phone traffic unaffected

## Build

```bash
./gradlew assembleDebug --no-daemon
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Install Tailscale on your phone and log in with the same account as your PC
2. Install the APK and grant overlay permission
3. Enter your PC's Tailscale IP (e.g. `100.x.x.x`) and tap Connect
4. Launch Cloe Desktop on your PC (the bridge automatically listens on `0.0.0.0:19850`)

## Features

- ✅ Floating window GIF animation display
- ✅ Idle random loop (switches every 8-15 seconds)
- ✅ Working mode (typing animation)
- ✅ Drag to reposition
- ✅ Tap to collapse into a small dot (dismiss), tap dot to expand (summon)
- ✅ Auto-reconnect WebSocket
- 🔲 Speak animation with audio playback
- 🔲 Reconnection notifications
