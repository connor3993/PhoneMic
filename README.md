# PhoneMic

> 把 Android 手机变成 Windows 电脑的局域网无线麦克风。
>
> Turn an Android phone into a low-latency wireless microphone for Windows over LAN.

[简体中文](#简体中文) · [English](#english)

![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Windows-blue)
![Audio](https://img.shields.io/badge/Audio-48%20kHz%20%7C%2016--bit-green)
![Latency](https://img.shields.io/badge/Target%20Latency-10--20%20ms-orange)
![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen)

---

## 简体中文

### 项目简介

**PhoneMic** 是一个面向局域网的手机麦克风传输系统：Android 手机采集麦克风音频，通过 UDP 实时发送到 Windows 电脑，再由电脑端写入虚拟声卡。配合 [VB-Audio Virtual Cable](https://vb-audio.com/Cable/)，微信、QQ、Discord、腾讯会议、OBS 和游戏都可以把手机当作真实麦克风使用。

音频链路采用 **48 kHz / 16-bit 单声道 PCM**，电脑端通过 `sounddevice` 回调播放，并针对手机与电脑之间的时钟漂移做动态补偿，目标是把正常局域网下的端到端延迟稳定在 **10–20 ms**。

### 核心特性

- **低延迟 UDP 音频**：默认 UDP `9000` 端口，10 ms 音频分块，严格限制播放队列，避免延迟越用越大。
- **时钟漂移补偿**：自动检测手机与电脑晶振差异，丢弃过期帧并维持低延迟。
- **实时监控面板**：浏览器打开 `http://localhost:9002`，查看延迟、抖动、丢包、队列深度、纠偏次数和 dBFS 电平。
- **JSON 状态接口**：`GET http://localhost:9002/stats`，方便脚本或 Agent 监控。
- **多种配对方式**：局域网 UDP 自动发现、终端二维码扫码、手动 IP / 端口。
- **Android 前台服务**：支持熄屏和后台收音，通知栏提供静音与断开控制。
- **麦克风增益与电平显示**：App 内支持 0%–200% 增益和实时 dBFS 波形。
- **自动匹配虚拟声卡**：电脑端优先寻找 VB-CABLE / VoiceMeeter 等虚拟音频设备，并将单声道 PCM 镜像到双声道输出。

### 工作原理

```text
Android App
  AudioRecord (48 kHz / 16-bit mono)
        │
        │ UDP 9000: PCM audio
        │ UDP 9001: LAN discovery
        ▼
Windows PC
  server-pc/server.py
        │
        ├── sounddevice callback ──> CABLE Input (VB-Audio Virtual Cable)
        ├── HTTP 9002 ─────────────> telemetry dashboard / GET /stats
        └── terminal ──────────────> pairing QR code + live meter
                                      │
                                      ▼
                            CABLE Output  <── 微信 / QQ / OBS / 游戏
```

> 其他软件需要选择 **`CABLE Output (VB-Audio Virtual Cable)`** 作为麦克风。`CABLE Input` 是电脑端写入音频的一端，不是给会议软件选择的麦克风。

### 环境要求

- Windows 10 / 11
- Android 8.0 或更高版本
- Python 3.10+（推荐）
- [VB-Audio Virtual Cable](https://vb-audio.com/Cable/)
- 手机与电脑连接同一个 Wi-Fi / 局域网，且路由器未开启客户端隔离

### 快速开始

#### 1. 安装虚拟声卡

安装 VB-CABLE 后，Windows 音频设备中会出现：

- `CABLE Input`：PhoneMic 电脑端写入音频
- `CABLE Output`：会议 / 游戏 / 直播软件读取麦克风

#### 2. 启动电脑端

```powershell
cd server-pc
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe server.py
```

也可以在创建好 `.venv` 后，直接双击项目根目录的 `启动电脑端服务.bat`。

服务启动后会显示局域网 IP、二维码和实时电平仪表盘。

#### 3. 安装 Android App

项目根目录已经包含预编译的 `PhoneMic.apk`，也可以自行构建：

```powershell
cd app-android
.\gradlew.bat assembleDebug
```

> Android 构建使用 JDK 17。请以你本机 Android Studio / Gradle 实际配置的 JDK 为准。

#### 4. 连接手机

1. 确保手机和电脑在同一个 Wi-Fi。
2. 打开 PhoneMic，点击“扫码配对”扫描终端二维码；或点击“局域网探测”自动发现电脑。
3. 如果自动发现失败，可手动输入电脑 IP 和 UDP 端口 `9000`。

#### 5. 在电脑软件中选择麦克风

在微信、QQ、Discord、腾讯会议、OBS 或游戏中：

- **扬声器 / 输出**：保持你的物理扬声器或耳机
- **麦克风 / 输入**：选择 `CABLE Output (VB-Audio Virtual Cable)`

### 服务端参数

```powershell
python server-pc/server.py `
  --device 12 `
  --port 9000 `
  --discovery-port 9001 `
  --telemetry-port 9002
```

| 参数 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `--device` | 自动选择 | `sounddevice` 输出设备 ID；通常优先选择 VB-CABLE |
| `--port` | `9000` | 接收手机 PCM 音频的 UDP 端口 |
| `--discovery-port` | `9001` | 局域网自动发现 UDP 端口 |
| `--telemetry-port` | `9002` | HTTP 监控面板和 JSON API 端口 |

### 监控接口

```text
http://localhost:9002          # 实时 Web 仪表盘
http://localhost:9002/stats    # JSON 状态快照
```

返回内容包括连接状态、客户端地址、延迟、抖动、丢包率、队列深度、纠偏次数和音量电平。

### 目录结构

```text
PhoneMic/
├── 启动电脑端服务.bat          # Windows 一键启动脚本
├── PhoneMic.apk               # 预编译 Android 安装包
├── server-pc/
│   ├── server.py              # UDP 音频接收、虚拟声卡输出、监控服务
│   ├── requirements.txt       # Python 依赖
│   └── test_client.py         # 本地音频链路测试脚本
└── app-android/
    ├── app/src/main/kotlin/com/phonemic/app/
    │   ├── audio/             # AudioRecord 采集
    │   ├── network/           # UDP 发包与局域网发现
    │   ├── service/           # 前台麦克风服务
    │   └── ui/                # Jetpack Compose 界面与扫码
    └── gradlew.bat            # Android 构建脚本
```

### 常见问题

**为什么有 `CABLE Input` 和 `CABLE Output`？**

VB-CABLE 是一根虚拟音频线：PhoneMic 把声音写进 `CABLE Input`，其他软件从 `CABLE Output` 读取。会议软件里应选择 `CABLE Output`。

**App 找不到电脑怎么办？**

- 确认手机和电脑在同一个局域网。
- 检查 Windows 防火墙是否允许 Python 接收 UDP `9000`、UDP `9001`，以及访问 TCP `9002`。
- 关闭路由器的 AP 隔离 / 客户端隔离。
- 尝试手动输入电脑局域网 IP。

**延迟逐渐变大怎么办？**

正常情况下电脑端会限制播放队列并自动纠偏。如果持续变慢，优先检查 Wi-Fi 信号、路由器拥塞、蓝牙共存干扰，以及是否误选了非虚拟声卡设备。

**没有声音怎么办？**

- 确认服务端已连接到手机。
- 确认 `CABLE Output` 是会议软件的输入设备。
- 确认系统默认播放设备仍是物理扬声器 / 耳机，而不是 `CABLE Input`。
- 查看 `http://localhost:9002` 是否有实时电平和丢包数据。

### 安全说明

PhoneMic 当前面向可信局域网使用，音频传输没有端到端加密，也没有账户认证。不要将 UDP `9000`、UDP `9001` 或监控端口 `9002` 直接暴露到公网。需要跨网络使用时，建议通过 VPN / 安全隧道接入。

### 许可证

当前仓库尚未声明开源许可证。对外分发、商用或二次发布前，请先补充 `LICENSE` 文件。

---

## English

### Overview

**PhoneMic** streams microphone audio from an Android phone to a Windows PC over a local network. The Android app captures audio and sends it over UDP; the Windows backend feeds it into a virtual audio cable. With [VB-Audio Virtual Cable](https://vb-audio.com/Cable/), apps such as WeChat, QQ, Discord, Tencent Meeting, OBS, and games can use the phone as a real microphone.

The audio path uses **48 kHz / 16-bit mono PCM**. The PC backend plays it through a `sounddevice` callback and compensates for clock drift between the phone and the computer. The target end-to-end latency is **10–20 ms** on a healthy LAN.

### Highlights

- **Low-latency UDP audio** on UDP port `9000`, with 10 ms chunks and a strictly bounded playback queue.
- **Clock-drift compensation** for small sample-rate differences between phone and PC.
- **Live telemetry dashboard** at `http://localhost:9002`: latency, jitter, packet loss, queue depth, drift corrections, and dBFS level.
- **JSON status endpoint** at `GET http://localhost:9002/stats`.
- **Three pairing modes**: LAN UDP discovery, terminal QR code, or manual IP / port.
- **Android foreground service** for screen-off and background capture, with notification controls.
- **Gain and level meter** from 0% to 200% with real-time dBFS display.
- **Virtual audio device detection** for VB-CABLE, VoiceMeeter, and similar devices; mono PCM is mirrored to stereo output.

### Architecture

```text
Android App
  AudioRecord (48 kHz / 16-bit mono)
        │
        │ UDP 9000: PCM audio
        │ UDP 9001: LAN discovery
        ▼
Windows PC
  server-pc/server.py
        │
        ├── sounddevice callback ──> CABLE Input (VB-Audio Virtual Cable)
        ├── HTTP 9002 ─────────────> telemetry dashboard / GET /stats
        └── terminal ──────────────> pairing QR code + live meter
                                      │
                                      ▼
                            CABLE Output  <── WeChat / QQ / OBS / games
```

> Select **`CABLE Output (VB-Audio Virtual Cable)`** as the microphone in other apps. `CABLE Input` is the sink written by PhoneMic, not the microphone selected by meeting software.

### Requirements

- Windows 10 / 11
- Android 8.0 or newer
- Python 3.10+ (recommended)
- [VB-Audio Virtual Cable](https://vb-audio.com/Cable/)
- Phone and PC on the same Wi-Fi / LAN, with client isolation disabled

### Quick Start

#### 1. Install the virtual audio cable

After installing VB-CABLE, Windows exposes:

- `CABLE Input`: written by the PhoneMic backend
- `CABLE Output`: read by meeting, game, and streaming apps

#### 2. Start the Windows backend

```powershell
cd server-pc
py -3 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe server.py
```

After the virtual environment exists, you can also double-click `启动电脑端服务.bat` in the repository root.

The server prints the LAN IP, pairing QR code, and live level meter.

#### 3. Install the Android app

A prebuilt `PhoneMic.apk` is included in the repository root. To build it yourself:

```powershell
cd app-android
.\gradlew.bat assembleDebug
```

> The Android build uses JDK 17. Use the JDK configured by your own Android Studio / Gradle setup.

#### 4. Connect the phone

1. Put the phone and PC on the same Wi-Fi network.
2. Open PhoneMic and scan the terminal QR code, or use LAN discovery.
3. If discovery fails, enter the PC's LAN IP and UDP port `9000` manually.

#### 5. Select the microphone in desktop apps

In WeChat, QQ, Discord, Tencent Meeting, OBS, or a game:

- **Speaker / output**: keep your physical speaker or headphones
- **Microphone / input**: select `CABLE Output (VB-Audio Virtual Cable)`

### Server Options

```powershell
python server-pc/server.py `
  --device 12 `
  --port 9000 `
  --discovery-port 9001 `
  --telemetry-port 9002
```

| Option | Default | Description |
| :--- | :--- | :--- |
| `--device` | Auto | `sounddevice` output device ID; VB-CABLE is preferred |
| `--port` | `9000` | UDP port receiving phone PCM audio |
| `--discovery-port` | `9001` | UDP port for LAN discovery |
| `--telemetry-port` | `9002` | HTTP dashboard and JSON API port |

### Monitoring

```text
http://localhost:9002          # Live web dashboard
http://localhost:9002/stats    # JSON status snapshot
```

The payload includes connection state, client address, latency, jitter, packet loss, queue depth, drift corrections, and audio level.

### Project Structure

```text
PhoneMic/
├── 启动电脑端服务.bat          # Windows one-click launcher
├── PhoneMic.apk               # Prebuilt Android APK
├── server-pc/
│   ├── server.py              # UDP receiver, virtual audio output, telemetry
│   ├── requirements.txt       # Python dependencies
│   └── test_client.py         # Local audio-path test client
└── app-android/
    ├── app/src/main/kotlin/com/phonemic/app/
    │   ├── audio/             # AudioRecord capture
    │   ├── network/           # UDP sender and LAN discovery
    │   ├── service/           # Foreground microphone service
    │   └── ui/                # Jetpack Compose UI and QR scanner
    └── gradlew.bat            # Android build script
```

### Troubleshooting

**Why are there both `CABLE Input` and `CABLE Output`?**

VB-CABLE is a virtual audio pair: PhoneMic writes into `CABLE Input`, and other apps read from `CABLE Output`. Select `CABLE Output` in meeting software.

**The app cannot find the PC.**

- Confirm both devices are on the same LAN.
- Allow Python through Windows Firewall for UDP `9000`, UDP `9001`, and TCP `9002`.
- Disable router AP isolation / client isolation.
- Try entering the PC's LAN IP manually.

**Latency keeps growing.**

The backend bounds the playback queue and compensates for drift. If latency still grows, check Wi-Fi signal quality, router congestion, Bluetooth coexistence, and whether the wrong output device was selected.

**There is no audio.**

- Confirm the server shows an active phone connection.
- Confirm `CABLE Output` is selected as the microphone in the target app.
- Keep the system playback device on your physical speaker / headphones, not `CABLE Input`.
- Check `http://localhost:9002` for live level and packet-loss data.

### Security

PhoneMic is designed for a trusted LAN. Audio traffic is not end-to-end encrypted and there is no account authentication. Do not expose UDP `9000`, UDP `9001`, or TCP `9002` directly to the public internet. Use a VPN or secure tunnel for remote access.

### License

No license file is currently declared. Add a `LICENSE` file before redistribution, commercial use, or public packaging.
