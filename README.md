# PhoneMic: 手机局域网麦克风 (Android 手机 -> Windows 电脑)

**PhoneMic** 是一个极低延迟、零重传积压的局域网麦克风传输系统。让你的 Android 手机变成电脑的高保真无线上网课/开会/语音开黑麦克风。

---

## 🌟 核心特性

- **低延迟纯净 PCM 流传输**：采用 UDP 传输 48,000Hz 16-bit 单声道原始音频，零重传机制避免 Wi-Fi 网络抖动下的延迟累积。
- **全系统软件通用识别**：搭配成熟的虚拟声卡驱动（如免费的 **VB-Audio Virtual Cable**），在 Windows 系统各语音软件（微信、QQ、Discord、腾讯会议、OBS、游戏等）中直接作为真实物理麦克风选择。
- **三重极速配对机制**：
  1. **局域网 UDP 自动发现**：App 启动自动探测局域网内的电脑端，一键秒连；
  2. **终端 ASCII 二维码扫码**：电脑端启动即在终端打印专属二维码，App 扫码即连；
  3. **手动 IP / 端口输入**：复杂跨子网或隔离网络兜底。
- **原生 Android 硬件级控制**：
  - **前台常驻服务 (Foreground Service)**：支持熄屏与后台稳定持续收音，通知栏带有一键静音与断开控制；
  - **Android 原生硬件降噪 (Noise Suppressor)** 与 **回声消除 (AEC)** 硬件算法开关；
  - **麦克风增益调节 (0% ~ 200%)**；
  - **实时分贝 (dB) 电平波形显示**，声学状态一目了然。
- **电脑端 Rich 终端仪表盘**：
  - 启动智能检测并高亮推荐虚拟音频设备；
  - 终端展示实时动态 VU 音量条、瞬时码率与客户端连接状态。

---

## 📁 目录结构

```text
F:\PhoneMic/
├── server-pc/             # 电脑端 Python 命令行后台
│   ├── server.py          # 核心音频接收服务与 Rich 仪表盘
│   ├── requirements.txt   # Python 依赖库
│   ├── run.bat            # Windows 一键启动脚本
│   └── test_client.py     # 本地音频链路自测脚本
└── app-android/           # 手机端原生 Kotlin + Jetpack Compose 工程
    ├── app/               # App 源代码与资源
    │   ├── src/main/kotlin/com/phonemic/app/
    │   │   ├── MainActivity.kt          # 主入口与权限管理
    │   │   ├── audio/                   # AudioRecord 采集与降噪增益
    │   │   ├── network/                 # UDP 发包与广播发现
    │   │   ├── service/                 # 前台录音服务与通知栏控制
    │   │   └── ui/                      # Jetpack Compose 界面与扫码
    │   └── AndroidManifest.xml
    └── gradlew.bat        # Gradle 构建脚本
```

---

## 🚀 快速上手指南

### 第一步：电脑端准备（虚拟声卡）

1. 若希望电脑所有软件将手机识别为系统麦克风，推荐下载并安装免费的虚拟声卡驱动：
   - 官方免费下载地址：[VB-Audio Virtual Cable](https://vb-audio.com/Cable/)
   - 安装完成后，电脑会新增两个音频设备：
     - 输出设备：`CABLE Input (VB-Audio Virtual Cable)`
     - 录音输入设备：`CABLE Output (VB-Audio Virtual Cable)`

### 第二步：启动电脑端服务

1. 进入 `F:\PhoneMic\server-pc` 目录；
2. 双击运行 `run.bat`（或在终端运行 `python server.py`）：
   - 程序会自动检测输出设备，按回车选用推荐的 `CABLE Input`（也可以选择耳机用于自听测试）；
   - 终端将打印出连接二维码与实时监控仪表盘。

### 第三步：安装与运行手机端 App

1. 在 Android Studio 中打开 `F:\PhoneMic\app-android` 工程，点击 Run 安装至手机；或者在命令行执行：
   ```bash
   cd F:\PhoneMic\app-android
   .\gradlew.bat installDebug
   ```
2. 确保手机与电脑连接至**同一个 Wi-Fi 局域网**；
3. 打开手机上的 **PhoneMic** App，授予麦克风与通知权限；
4. 点击“**扫码配对**”对准电脑终端屏幕上的二维码，或点击“**局域网探测**”自动发现电脑，点击连接即可！

### 第四步：在电脑软件中选用麦克风

在微信、QQ、Discord、腾讯会议或系统“声音设置”中：
- 将**输入设备（麦克风）**选择为：`CABLE Output (VB-Audio Virtual Cable)`。
- 现在对着手机说话，电脑端就会实时采入声音！
