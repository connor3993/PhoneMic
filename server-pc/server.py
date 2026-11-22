#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PhoneMic PC Backend Server
Receives raw 48kHz 16-bit mono PCM via UDP and feeds into selected audio output (e.g., VB-CABLE Input).
Includes UDP auto-discovery, terminal QR code, and real-time Rich dashboard with VU meter.
"""

import sys
import time
import socket
import threading
import json
import io
import argparse
import numpy as np
import sounddevice as sd
import qrcode
from rich.console import Console
from rich.layout import Layout
from rich.panel import Panel
from rich.live import Live
from rich.table import Table
from rich.text import Text

DEFAULT_PORT = 9000
DISCOVERY_PORT = 9001
SAMPLE_RATE = 48000
CHANNELS = 1
DTYPE = 'int16'
BYTES_PER_SAMPLE = 2  # 16-bit = 2 bytes
BUFFER_CHUNK_SAMPLES = 480  # 10ms at 48kHz = 480 samples = 960 bytes

console = Console()

def get_best_lan_ip():
    """Detect the real local Wi-Fi / LAN IP address, excluding VPN/Clash TUN/virtual adapters."""
    candidates = []
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            # Filter out virtual/proxy subnets
            if ip.startswith(("127.", "169.254.", "198.18.", "198.19.", "26.", "172.23.")):
                continue
            candidates.append(ip)
    except Exception:
        pass

    # 1. Prefer standard 192.168.x.x
    for ip in candidates:
        if ip.startswith("192.168."):
            return ip

    # 2. Prefer 10.x.x.x
    for ip in candidates:
        if ip.startswith("10."):
            return ip

    # 3. Prefer 172.16~31
    for ip in candidates:
        if ip.startswith("172."):
            return ip

    if candidates:
        return candidates[0]

    return "192.168.1.138"


def list_and_select_device(cli_device_idx=None):
    """List output devices and recommend virtual audio devices."""
    devices = sd.query_devices()
    hostapis = sd.query_hostapis()
    
    output_devices = []
    recommended_idx = None

    keywords = ["cable input", "virtual audio", "cable-a", "cable-b", "sonar - microphone", "voicemeeter"]

    for idx, dev in enumerate(devices):
        if dev['max_output_channels'] > 0:
            api_name = hostapis[dev['hostapi']]['name']
            name = dev['name']
            output_devices.append((idx, name, api_name, dev['max_output_channels']))
            
            # Check if it matches virtual cable keywords
            if recommended_idx is None:
                for kw in keywords:
                    if kw in name.lower():
                        recommended_idx = idx
                        break

    if cli_device_idx is not None:
        try:
            val = int(cli_device_idx)
            if 0 <= val < len(devices) and devices[val]['max_output_channels'] > 0:
                return val
        except Exception:
            pass

    table = Table(title="[bold cyan]可用的音频输出设备列表[/bold cyan]", show_header=True, header_style="bold magenta")
    table.add_column("序号", style="dim", width=6)
    table.add_column("设备名称", min_width=35)
    table.add_column("驱动架构", width=18)
    table.add_column("声道数", width=8)
    table.add_column("推荐度", width=12)

    for idx, name, api_name, channels in output_devices:
        is_rec = (idx == recommended_idx)
        rec_tag = "[bold green]★ 推荐 (虚拟)[/bold green]" if is_rec else ""
        style = "bold green" if is_rec else ""
        table.add_row(str(idx), name, api_name, str(channels), rec_tag, style=style)

    console.print(table)

    default_out_dev = sd.default.device[1] if sd.default.device[1] >= 0 else output_devices[0][0]
    rec_target = recommended_idx if recommended_idx is not None else default_out_dev

    rec_name = devices[rec_target]['name']
    if recommended_idx is not None:
        console.print(f"[bold green]✓ 自动选定推荐虚拟音频设备：[{rec_target}] {rec_name}[/bold green]")
    else:
        console.print(f"[bold green]✓ 自动选定默认音频输出设备：[{rec_target}] {rec_name}[/bold green]")

    return rec_target


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.connected_client = None
        self.last_packet_time = 0
        self.packets_total = 0
        self.bytes_total = 0
        self.current_db = -96.0
        self.peak_db = -96.0
        self.bitrate_kbps = 0.0
        self._bytes_in_sec = 0
        self._last_sec_time = time.time()

    def update_audio(self, client_addr, pcm_data):
        now = time.time()
        with self.lock:
            self.connected_client = client_addr
            self.last_packet_time = now
            self.packets_total += 1
            n_bytes = len(pcm_data)
            self.bytes_total += n_bytes
            self._bytes_in_sec += n_bytes

            # Calculate RMS dB
            if len(pcm_data) >= 2:
                samples = np.frombuffer(pcm_data, dtype=np.int16).astype(np.float32)
                rms = np.sqrt(np.mean(samples ** 2)) if len(samples) > 0 else 0
                if rms > 1e-4:
                    db = 20 * np.log10(rms / 32767.0)
                else:
                    db = -96.0
                self.current_db = max(-96.0, float(db))
                if self.current_db > self.peak_db:
                    self.peak_db = self.current_db
                else:
                    # Slow decay for peak
                    self.peak_db = max(-96.0, self.peak_db - 0.5)

            # Update bitrate every 1 second
            if now - self._last_sec_time >= 1.0:
                self.bitrate_kbps = (self._bytes_in_sec * 8.0) / 1024.0 / (now - self._last_sec_time)
                self._bytes_in_sec = 0
                self._last_sec_time = now


class AudioServer:
    def __init__(self, device_idx, port=DEFAULT_PORT, discovery_port=DISCOVERY_PORT):
        self.device_idx = device_idx
        self.port = port
        self.discovery_port = discovery_port
        self.lan_ip = get_best_lan_ip()
        self.running = False
        self.stats = Stats()
        self.audio_stream = None
        self.udp_sock = None
        self.discovery_sock = None

    def start_audio_stream(self):
        """Open sounddevice output stream."""
        self.audio_stream = sd.RawOutputStream(
            samplerate=SAMPLE_RATE,
            channels=CHANNELS,
            dtype=DTYPE,
            device=self.device_idx,
            blocksize=BUFFER_CHUNK_SAMPLES
        )
        self.audio_stream.start()

    def run_udp_audio(self):
        """Listen for UDP raw PCM packets and write directly to audio stream."""
        try:
            self.udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)  # 1MB buffer
            self.udp_sock.bind(("0.0.0.0", self.port))
            self.udp_sock.settimeout(0.5)
        except Exception as e:
            console.print(f"[bold red]UDP 接收服务启动失败（端口 {self.port} 可能已被占用）：{e}[/bold red]")
            self.running = False
            return

        while self.running:
            try:
                data, addr = self.udp_sock.recvfrom(4096)
                if data:
                    self.stats.update_audio(addr, data)
                    if self.audio_stream and self.audio_stream.active:
                        self.audio_stream.write(data)
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    # Ignore occasional write buffer hiccups
                    pass

    def run_discovery_service(self):
        """Handle UDP broadcast discovery and beacon."""
        self.discovery_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.discovery_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.discovery_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.discovery_sock.bind(("0.0.0.0", self.discovery_port))
        self.discovery_sock.settimeout(1.0)

        last_beacon_time = 0

        while self.running:
            now = time.time()
            # Send periodic beacon every 3s
            if now - last_beacon_time >= 3.0:
                beacon_msg = json.dumps({
                    "type": "PHONEMIC_BEACON",
                    "ip": self.lan_ip,
                    "port": self.port,
                    "name": socket.gethostname(),
                    "rate": SAMPLE_RATE,
                    "channels": CHANNELS
                }).encode('utf-8')
                try:
                    self.discovery_sock.sendto(beacon_msg, ("255.255.255.255", self.discovery_port))
                except Exception:
                    pass
                last_beacon_time = now

            # Listen for ping from phone
            try:
                data, addr = self.discovery_sock.recvfrom(2048)
                msg_str = data.decode('utf-8', errors='ignore')
                if "PHONEMIC_DISCOVERY_PING" in msg_str:
                    pong_msg = json.dumps({
                        "type": "PHONEMIC_DISCOVERY_PONG",
                        "ip": self.lan_ip,
                        "port": self.port,
                        "name": socket.gethostname(),
                        "rate": SAMPLE_RATE,
                        "channels": CHANNELS
                    }).encode('utf-8')
                    self.discovery_sock.sendto(pong_msg, addr)
            except socket.timeout:
                continue
            except Exception:
                continue

    def generate_ascii_qr(self):
        """Generate compact ASCII QR code string."""
        qr_data = f"phonemic://{self.lan_ip}:{self.port}?rate={SAMPLE_RATE}&channels={CHANNELS}"
        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_L,
            box_size=1,
            border=1
        )
        qr.add_data(qr_data)
        qr.make(fit=True)
        
        output = io.StringIO()
        qr.print_ascii(out=output, invert=True)
        return qr_data, output.getvalue()


def build_vu_bar(current_db, peak_db, width=28):
    """Render a colored VU bar from -60dB to 0dB."""
    min_db = -60.0
    max_db = 0.0
    
    clamped_cur = max(min_db, min(max_db, current_db))
    cur_ratio = (clamped_cur - min_db) / (max_db - min_db)
    cur_len = int(cur_ratio * width)

    clamped_peak = max(min_db, min(max_db, peak_db))
    peak_ratio = (clamped_peak - min_db) / (max_db - min_db)
    peak_pos = int(peak_ratio * width)

    bar_chars = []
    for i in range(width):
        if i < cur_len:
            # Color gradient: green -> yellow -> red
            if i < int(width * 0.65):
                bar_chars.append("[green]█[/green]")
            elif i < int(width * 0.85):
                bar_chars.append("[yellow]█[/yellow]")
            else:
                bar_chars.append("[bold red]█[/bold red]")
        elif i == peak_pos:
            bar_chars.append("[bold white]│[/bold white]")
        else:
            bar_chars.append("[dim]░[/dim]")
            
    return "".join(bar_chars)


def main():
    parser = argparse.ArgumentParser(description="PhoneMic PC Backend Server")
    parser.add_argument("--device", type=int, default=None, help="Audio output device ID")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="UDP audio port")
    parser.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT, help="UDP discovery port")
    args = parser.parse_args()

    console.clear()
    console.print(Panel.fit(
        "[bold cyan]🎙️ PhoneMic 手机局域网麦克风电脑端服务[/bold cyan]\n"
        "[dim]低延迟 UDP 原始 PCM 48kHz 音频接收服务[/dim]",
        border_style="cyan"
    ))

    # 1. Device Selection
    device_idx = list_and_select_device(cli_device_idx=args.device)
    dev_info = sd.query_devices(device_idx)
    console.print(f"\n[green]✓ 已选定输出目标：[/green] [bold]{dev_info['name']}[/bold] (ID: {device_idx})\n")

    # 2. Setup Server
    server = AudioServer(device_idx=device_idx, port=args.port, discovery_port=args.discovery_port)
    qr_data, ascii_qr = server.generate_ascii_qr()

    try:
        server.start_audio_stream()
    except Exception as e:
        console.print(f"[bold red]启动音频流失败：{e}[/bold red]")
        sys.exit(1)

    server.running = True

    # 3. Threads for UDP and Discovery
    t_udp = threading.Thread(target=server.run_udp_audio, daemon=True)
    t_discovery = threading.Thread(target=server.run_discovery_service, daemon=True)
    t_udp.start()
    t_discovery.start()

    # 4. Live UI Loop
    try:
        with Live(console=console, refresh_per_second=10) as live:
            while True:
                # Client status
                now = time.time()
                with server.stats.lock:
                    client = server.stats.connected_client
                    last_time = server.stats.last_packet_time
                    pkts = server.stats.packets_total
                    cur_db = server.stats.current_db
                    peak_db = server.stats.peak_db
                    kbps = server.stats.bitrate_kbps

                is_active = (now - last_time < 2.0) and (last_time > 0)
                status_color = "bold green" if is_active else "bold yellow"
                status_text = f"[{status_color}]{'🟢 手机已连接，正在收音中' if is_active else '🟡 等待手机连接...'}[/{status_color}]"
                client_str = f"{client[0]}:{client[1]}" if (client and is_active) else "无"

                vu_bar = build_vu_bar(cur_db, peak_db, width=28)
                db_display = f"{cur_db:5.1f} dB" if is_active else " -inf dB"

                info_table = Table.grid(padding=(0, 2))
                info_table.add_column(style="bold cyan")
                info_table.add_column()

                info_table.add_row("电脑局域网 IP:", f"[bold white]{server.lan_ip}[/bold white]")
                info_table.add_row("音频接收端口:", f"[bold white]{server.port}[/bold white]")
                info_table.add_row("广播发现端口:", f"[bold white]{server.discovery_port}[/bold white]")
                info_table.add_row("音频输出设备:", f"[bold green]{dev_info['name']}[/bold green]")
                info_table.add_row("采样格式:", "[white]48000 Hz, 16-bit, Mono[/white]")
                info_table.add_row("连接状态:", status_text)
                info_table.add_row("当前客户端:", f"[white]{client_str}[/white]")
                info_table.add_row("实时接收码率:", f"[white]{kbps:5.1f} kbps[/white] (累计包数: {pkts})")
                info_table.add_row("实时音量电平:", f"{vu_bar} [bold]{db_display}[/bold]")

                grid = Table.grid(expand=True, padding=(0, 2))
                grid.add_column(ratio=1)
                grid.add_column(ratio=1)

                qr_panel = Panel(
                    ascii_qr + f"\n[dim center]{qr_data}[/dim center]",
                    title="[bold yellow]手机扫码极速连接[/bold yellow]",
                    border_style="yellow"
                )

                dash_panel = Panel(
                    info_table,
                    title="[bold cyan]实时监控仪表盘[/bold cyan]",
                    border_style="cyan"
                )

                grid.add_row(qr_panel, dash_panel)

                main_panel = Panel(
                    grid,
                    title=f"[bold green]PhoneMic 电脑端麦克风网关 v1.0[/bold green] (按 Ctrl+C 退出)",
                    subtitle="[dim]提示：在 Discord / 微信 / 录音软件中将输入设备设为该虚拟声卡即可直接使用[/dim]",
                    border_style="blue"
                )

                live.update(main_panel)
                time.sleep(0.1)

    except KeyboardInterrupt:
        console.print("\n[yellow]正在停止服务...[/yellow]")
    finally:
        server.running = False
        if server.audio_stream:
            server.audio_stream.stop()
            server.audio_stream.close()
        console.print("[green]服务已安全退出。[/green]")


if __name__ == "__main__":
    main()
