# PhoneMic: LAN Microphone (Android Phone → Windows PC)

[中文说明](README.md) | **English**

**PhoneMic** is a high-fidelity, ultra-low-latency (under 20 ms) LAN microphone system with zero retransmission backlog. It turns an Android phone into a high-quality wireless microphone for online classes, meetings, games, and voice chat on a Windows PC.

---

## Highlights and Architecture

- **Hardware-style asynchronous callback engine with clock-drift locking**
  - The PC side uses a professional audio callback path, fully decoupling the UDP receive thread from sound-card playback.
  - A dynamic drift-compensation mechanism detects the tiny clock difference between the phone and PC (for example, 48010 Hz vs 48000 Hz), drops stale frames when necessary, and keeps end-to-end latency locked in the 10–20 ms range instead of growing over time.
- **Zero-overhead live telemetry dashboard (port 9002)**
  - A lightweight, always-on telemetry service runs outside the audio hot path.
  - Open `http://localhost:9002` in a browser to see end-to-end latency, jitter, packet loss, drift corrections, and a live VU meter.
  - `GET /stats` returns the same data as JSON for scripts and agents.
- **Full-band clear voice (VOICE_RECOGNITION)**
  - The Android app captures with a speech-optimized source so voice detail remains clear.
  - It avoids Android's VoIP call mode, preventing audio loops when the phone is connected to a Bluetooth headset.
- **Stereo broadcast mapping**
  - The PC mirrors mono PCM into a stereo buffer, preserving the 48,000 Hz sample rate and matching the VB-CABLE workflow.
- **Works with ordinary Windows applications**
  - With a virtual audio cable such as **VB-Audio Virtual Cable**, the stream appears as a normal microphone in WeChat, QQ, Discord, Tencent Meeting, OBS, games, and other apps.
- **Three fast pairing paths**
  1. **UDP LAN discovery**: the app finds the PC automatically and connects with one tap.
  2. **Terminal ASCII QR code**: the PC prints a QR code; scan it from the app.
  3. **Manual IP and port**: a fallback for isolated or cross-subnet networks.
- **Native Android controls**
  - **Foreground service**: keeps capturing with the screen off and provides mute/disconnect actions in the notification.
  - **Microphone gain**: 0%–200%.
  - **Live dBFS level meter** for immediate acoustic feedback.
- **Bilingual UI with in-app switching**
  - Tap `EN` / `中` in the Android app's top bar to switch immediately; the choice is saved on the device.
  - The PC terminal asks for a language on first launch and remembers it for later runs.
- **Clean terminal dashboard**
  - Double-click to start, automatically select the virtual cable, and see the QR code plus live levels without noisy logs.

---

## Project Layout

```text
F:\PhoneMic/
├── 启动电脑端服务.bat      # One-click Chinese launcher
├── start-pc-server-en.bat  # One-click English launcher
├── PhoneMic.apk            # Prebuilt Android APK
├── README.md               # Chinese documentation
├── README.en.md            # English documentation
├── server-pc/              # Python PC backend
│   ├── server.py           # Async callback audio gateway and terminal dashboard
│   ├── requirements.txt    # Python dependencies
│   └── test_client.py      # Local audio-path test client
└── app-android/            # Native Kotlin + Jetpack Compose app
    ├── app/                # App source and resources
    │   ├── src/main/kotlin/com/phonemic/app/
    │   │   ├── MainActivity.kt          # Entry point, permissions, language switch
    │   │   ├── i18n/                    # Language context and persistence
    │   │   ├── audio/                   # AudioRecord capture and voice source
    │   │   ├── network/                 # UDP sender and discovery
    │   │   ├── service/                 # Foreground recording service
    │   │   └── ui/                      # Compose UI and QR scanner
    │   └── AndroidManifest.xml
    └── gradlew.bat        # Gradle wrapper
```

---

## Quick Start

### 1. Install a virtual audio cable on the PC

1. Download and install the free [VB-Audio Virtual Cable](https://vb-audio.com/Cable/).
2. After installation, Windows exposes:
   - **CABLE Input**: the injection endpoint used by the PC backend.
   - **CABLE Output**: the recording endpoint used by other applications as a microphone.

### 2. Start the PC service

From the project root, either:

- double-click [`启动电脑端服务.bat`](file:///F:/PhoneMic/启动电脑端服务.bat) for the Chinese UI, or
- double-click [`start-pc-server-en.bat`](file:///F:/PhoneMic/start-pc-server-en.bat) for the English UI.

The service detects the LAN address and the best virtual audio output automatically, then prints a pairing QR code and live level dashboard.

For advanced use, run it directly:

```powershell
cd F:\PhoneMic\server-pc
.venv\Scripts\python.exe server.py --lang en
```

Useful CLI options:

```text
--lang auto|zh|en       UI language (default: auto)
--device ID             Audio output device ID
--port PORT             UDP audio port (default: 9000)
--discovery-port PORT   UDP discovery port (default: 9001)
--telemetry-port PORT   HTTP telemetry port (default: 9002)
```

`auto` reuses the saved language (or detects the system locale when no choice exists); `zh` forces Chinese and `en` forces English. Standard `--help` is available.

### Language switching

- **Android app**: tap `EN` / `中` in the top bar. The app rebuilds immediately and saves the choice locally.
- **PC server**: choose `1. English` or `2. 中文` on first launch. The choice is saved to `%APPDATA%\PhoneMic\config.json` and reused automatically.
- To choose again, run `server.py --reset-language`; to force English temporarily, run `server.py --lang en`.

### 3. Install and connect the Android app

1. Install [`PhoneMic.apk`](file:///F:/PhoneMic/PhoneMic.apk) and open it.
2. Make sure the phone and PC are on the **same Wi-Fi LAN**.
3. Tap **Scan QR** and point it at the QR code in the PC terminal, or tap **LAN Discovery** and connect to the detected PC.

### 4. Select the microphone in PC applications

In WeChat, QQ, Discord, Tencent Meeting, a game, or OBS:

- **Output / Speaker**: keep your normal **speaker (Realtek)** or **headphones** so PC audio still plays normally.
- **Input / Microphone**: select **`CABLE Output (VB-Audio Virtual Cable)`**.
- Speak into the phone; other participants receive the phone microphone with very low latency.

---

## FAQ and Tips

### Why does `CABLE Input` appear in the playback list?

VB-CABLE is a virtual patch cable. The Python backend writes audio into `CABLE Input`; other applications read it from `CABLE Output`.

To hide an unused endpoint, press `Win + R`, run `mmsys.cpl`, open the **Playback** tab, right-click the extra `CABLE In 16ch` device, and choose **Disable**. As long as your default playback device remains the physical speaker or headphones, normal PC audio is unaffected.

### Why is the level shown as a negative number?

The dashboard uses standard **dBFS (decibels relative to full scale)**:

- `0 dB` is the digital ceiling; exceeding it causes clipping.
- `-12 dB` to `-18 dB` is a good target for clear speech.
- Normal audio levels are negative values below `0 dB`.

### Which port should I open in the firewall?

Allow inbound UDP `9000` and `9001`, and TCP `9002` if you want to view the web dashboard from another device. On a trusted home LAN, Windows Firewall usually prompts once when the server starts.

---

## Design Notes for CLI Software

The PC backend is intentionally a normal command-line program rather than a separate GUI:

- Keep `--help` and flags stable and machine-friendly.
- Use English as the default help/API language, with `--lang` for the human-facing dashboard.
- Separate the audio hot path from telemetry, logging, and dashboard rendering.
- Keep stdout useful for humans and `GET /stats` useful for automation.
- Prefer one executable entry point with configuration flags over several duplicated builds.

This keeps the project easy to run locally, easy to script, and easy to package later.
