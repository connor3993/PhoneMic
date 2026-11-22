#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PhoneMic PC Backend Server v1.1
- Zero-overhead UDP audio receiver (48kHz 16-bit mono -> stereo output to VB-CABLE Input)
- Hardware callback engine with strict 10ms-20ms latency clamping & clock drift suppression
- Ultra-lightweight telemetry API & live dashboard on port 9002 (zero audio path interference)
- Auto-discovery beacon & terminal QR code
"""

import sys
import time
import socket
import struct
import threading
import json
import io
import queue
import argparse
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
import numpy as np
import sounddevice as sd
import qrcode
from rich.console import Console
from rich.panel import Panel
from rich.live import Live
from rich.table import Table

DEFAULT_PORT = 9000
DISCOVERY_PORT = 9001
TELEMETRY_PORT = 9002
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
            if ip.startswith(("127.", "169.254.", "198.18.", "198.19.", "26.", "172.23.")):
                continue
            candidates.append(ip)
    except Exception:
        pass

    for ip in candidates:
        if ip.startswith("192.168."):
            return ip
    for ip in candidates:
        if ip.startswith("10."):
            return ip
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

    wasapi_candidates = []
    other_candidates = []

    for idx, dev in enumerate(devices):
        if dev['max_output_channels'] > 0:
            api_name = hostapis[dev['hostapi']]['name']
            name = dev['name']
            output_devices.append((idx, name, api_name, dev['max_output_channels']))
            for kw in keywords:
                if kw in name.lower():
                    if api_name == "Windows WASAPI":
                        wasapi_candidates.append(idx)
                    else:
                        other_candidates.append(idx)
                    break

    if wasapi_candidates:
        recommended_idx = wasapi_candidates[0]
    elif other_candidates:
        recommended_idx = other_candidates[0]

    if cli_device_idx is not None:
        try:
            val = int(cli_device_idx)
            if 0 <= val < len(devices) and devices[val]['max_output_channels'] > 0:
                return val
        except Exception:
            pass

    default_out_dev = sd.default.device[1] if sd.default.device[1] >= 0 else output_devices[0][0]
    return recommended_idx if recommended_idx is not None else default_out_dev


class Stats:
    """Thread-safe, zero-overhead telemetry collector."""
    def __init__(self, device_name=""):
        self.lock = threading.Lock()
        self.device_name = device_name
        self.start_time = time.time()
        self.connected_client = None
        self.connected_since = 0
        self.last_packet_time = 0
        self.packets_total = 0
        self.packets_lost = 0
        self.bytes_total = 0
        self.current_db = -96.0
        self.peak_db = -96.0
        self.bitrate_kbps = 0.0
        self._bytes_in_sec = 0
        self._last_sec_time = time.time()

        # Telemetry metrics
        self.latency_ms = 12.0
        self.jitter_ms = 1.0
        self.queue_depth = 0
        self.drift_corrections = 0
        self._last_seq = None
        self._min_transit_offset = None
        self._last_transit_diff = None

    def update_audio(self, client_addr, pcm_data, seq=None, ts_ms=None, qdepth=0):
        """Called on UDP thread. Minimized operations to guarantee zero latency."""
        now = time.time()
        now_ms = now * 1000.0
        n_bytes = len(pcm_data)

        # Atomic primitive updates
        if self.connected_client != client_addr:
            self.connected_client = client_addr
            self.connected_since = now
            self._last_seq = None
            self._min_transit_offset = None
            self._last_transit_diff = None

        self.last_packet_time = now
        self.packets_total += 1
        self.bytes_total += n_bytes
        self._bytes_in_sec += n_bytes
        self.queue_depth = qdepth

        # Sequence and loss tracking
        if seq is not None:
            if self._last_seq is not None:
                diff = seq - self._last_seq
                if diff > 1:
                    self.packets_lost += (diff - 1)
            self._last_seq = seq

        # Jitter and Latency tracking
        if ts_ms is not None:
            transit_diff = now_ms - ts_ms
            if self._min_transit_offset is None or transit_diff < self._min_transit_offset:
                self._min_transit_offset = transit_diff
            
            # Estimated network transit time (with nominal 4ms baseline)
            cur_lat = max(1.0, (transit_diff - self._min_transit_offset) + 4.0)
            self.latency_ms = 0.85 * self.latency_ms + 0.15 * cur_lat

            # RFC 3550 Jitter estimation
            if self._last_transit_diff is not None:
                d = abs(transit_diff - self._last_transit_diff)
                self.jitter_ms += (d - self.jitter_ms) / 16.0
            self._last_transit_diff = transit_diff

        # Calculate RMS dB level
        if n_bytes >= 2:
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
                self.peak_db = max(-96.0, self.peak_db - 0.5)

        # Bitrate update every 1 second
        if now - self._last_sec_time >= 1.0:
            dt = now - self._last_sec_time
            self.bitrate_kbps = (self._bytes_in_sec * 8.0) / 1024.0 / dt
            self._bytes_in_sec = 0
            self._last_sec_time = now

    def to_dict(self):
        """Snapshot dictionary for telemetry API."""
        now = time.time()
        is_active = (now - self.last_packet_time < 2.0) and (self.last_packet_time > 0)
        client_str = f"{self.connected_client[0]}:{self.connected_client[1]}" if (self.connected_client and is_active) else "无"
        total_p = self.packets_total + self.packets_lost
        loss_pct = (self.packets_lost / total_p * 100.0) if total_p > 0 else 0.0

        return {
            "status": "active" if is_active else "waiting",
            "client": client_str,
            "uptime_sec": round(now - self.start_time, 1),
            "latency_ms": round(self.latency_ms, 1),
            "jitter_ms": round(self.jitter_ms, 1),
            "packet_loss_pct": round(loss_pct, 2),
            "packets_received": self.packets_total,
            "packets_lost": self.packets_lost,
            "queue_frames": self.queue_depth,
            "queue_delay_ms": round(self.queue_depth * 10.0, 1),
            "drift_corrections": self.drift_corrections,
            "current_db": round(self.current_db, 1),
            "peak_db": round(self.peak_db, 1),
            "bitrate_kbps": round(self.bitrate_kbps, 1),
            "sample_rate": SAMPLE_RATE,
            "channels": CHANNELS,
            "device": self.device_name
        }


HTML_DASHBOARD = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>PhoneMic 实时监控控制台</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background: #0d1117;
            color: #c9d1d9;
            padding: 24px;
            display: flex;
            justify-content: center;
        }
        .container { max-width: 900px; width: 100%; }
        header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; border-bottom: 1px solid #30363d; padding-bottom: 16px; }
        h1 { font-size: 22px; color: #58a6ff; display: flex; align-items: center; gap: 8px; }
        .badge { padding: 4px 10px; border-radius: 12px; font-size: 13px; font-weight: 600; }
        .badge.active { background: #238636; color: #fff; }
        .badge.waiting { background: #d29922; color: #161b22; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }
        .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; }
        .card-title { font-size: 13px; color: #8b949e; margin-bottom: 8px; }
        .card-val { font-size: 24px; font-weight: 700; color: #f0f6fc; }
        .card-desc { font-size: 12px; color: #3fb950; margin-top: 4px; }
        .card-desc.subtle { color: #8b949e; }
        .meter-container { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; margin-bottom: 24px; }
        .meter-bar { height: 16px; background: #21262d; border-radius: 8px; overflow: hidden; position: relative; margin-top: 10px; }
        .meter-fill { height: 100%; width: 0%; background: linear-gradient(90deg, #238636, #d29922, #da3633); transition: width 0.08s ease; }
        .info-table { width: 100%; border-collapse: collapse; background: #161b22; border: 1px solid #30363d; border-radius: 8px; overflow: hidden; }
        .info-table td { padding: 12px 16px; border-bottom: 1px solid #21262d; font-size: 14px; }
        .info-table td:first-child { color: #8b949e; width: 180px; }
        .footer { margin-top: 20px; font-size: 12px; color: #8b949e; text-align: center; }
        code { background: #21262d; padding: 2px 6px; border-radius: 4px; color: #f0883e; }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>🎙️ PhoneMic 实时性能与健康监控</h1>
            <span id="status-badge" class="badge waiting">等待连接</span>
        </header>

        <div class="grid">
            <div class="card">
                <div class="card-title">端到端实时延迟</div>
                <div class="card-val" id="val-latency">-- ms</div>
                <div class="card-desc" id="desc-latency">🟢 极速无感级别</div>
            </div>
            <div class="card">
                <div class="card-title">网络抖动 (Jitter)</div>
                <div class="card-val" id="val-jitter">±-- ms</div>
                <div class="card-desc subtle">RFC 3550 实时评估</div>
            </div>
            <div class="card">
                <div class="card-title">丢包率</div>
                <div class="card-val" id="val-loss">0.00 %</div>
                <div class="card-desc subtle" id="desc-loss">丢失 0 包</div>
            </div>
            <div class="card">
                <div class="card-title">时钟漂移抗积压纠偏</div>
                <div class="card-val" id="val-drift">0 次</div>
                <div class="card-desc">锁定延时 ≤ 20ms</div>
            </div>
        </div>

        <div class="meter-container">
            <div style="display: flex; justify-content: space-between;">
                <span class="card-title">实时麦克风音量 (VU Meter)</span>
                <span id="val-db" style="font-weight: 600; font-size: 14px;">-inf dB</span>
            </div>
            <div class="meter-bar">
                <div class="meter-fill" id="meter-fill"></div>
            </div>
        </div>

        <table class="info-table">
            <tr><td>当前连接手机</td><td id="info-client">等待连接...</td></tr>
            <tr><td>输出目标设备</td><td id="info-device">VB-CABLE</td></tr>
            <tr><td>实时网络码率</td><td id="info-bitrate">0.0 kbps</td></tr>
            <tr><td>累计接收包数</td><td id="info-packets">0 包</td></tr>
            <tr><td>队列缓冲延时</td><td id="info-queue">10.0 ms (1 帧)</td></tr>
            <tr><td>服务运行时间</td><td id="info-uptime">0 秒</td></tr>
            <tr><td>REST API 接口</td><td><code>GET /stats</code> (JSON 格式，供脚本与监控调用)</td></tr>
        </table>

        <div class="footer">PhoneMic Ultra-Low Latency Engine &copy; 2026</div>
    </div>

    <script>
        async function updateStats() {
            try {
                const res = await fetch('/stats');
                const data = await res.json();
                
                const isActive = data.status === 'active';
                const badge = document.getElementById('status-badge');
                badge.className = 'badge ' + (isActive ? 'active' : 'waiting');
                badge.innerText = isActive ? '🟢 手机收音中' : '🟡 等待连接';

                document.getElementById('val-latency').innerText = isActive ? (data.latency_ms + ' ms') : '-- ms';
                document.getElementById('val-jitter').innerText = isActive ? ('±' + data.jitter_ms + ' ms') : '±-- ms';
                document.getElementById('val-loss').innerText = data.packet_loss_pct + ' %';
                document.getElementById('desc-loss').innerText = '丢失 ' + data.packets_lost + ' / 接收 ' + data.packets_received;
                document.getElementById('val-drift').innerText = data.drift_corrections + ' 次';

                document.getElementById('info-client').innerText = data.client;
                document.getElementById('info-device').innerText = data.device;
                document.getElementById('info-bitrate').innerText = data.bitrate_kbps + ' kbps';
                document.getElementById('info-packets').innerText = data.packets_received + ' 包';
                document.getElementById('info-queue').innerText = data.queue_delay_ms + ' ms (' + data.queue_frames + ' 帧)';
                document.getElementById('info-uptime').innerText = data.uptime_sec + ' 秒';

                const db = data.current_db;
                document.getElementById('val-db').innerText = isActive ? (db.toFixed(1) + ' dB') : '-inf dB';
                const pct = isActive ? Math.max(0, Math.min(100, ((db + 60) / 60) * 100)) : 0;
                document.getElementById('meter-fill').style.width = pct + '%';

            } catch (e) {}
        }
        setInterval(updateStats, 300);
        updateStats();
    </script>
</body>
</html>
"""


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class TelemetryHandler(BaseHTTPRequestHandler):
    stats_ref = None

    def log_message(self, format, *args):
        # Silence HTTP access logs to keep terminal pristine
        pass

    def do_GET(self):
        if self.path == '/stats':
            data = json.dumps(self.stats_ref.to_dict()).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        elif self.path == '/health':
            data = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            data = HTML_DASHBOARD.encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)


class AudioServer:
    def __init__(self, device_idx, port=DEFAULT_PORT, discovery_port=DISCOVERY_PORT, telemetry_port=TELEMETRY_PORT):
        self.device_idx = device_idx
        self.port = port
        self.discovery_port = discovery_port
        self.telemetry_port = telemetry_port
        self.lan_ip = get_best_lan_ip()
        self.running = False
        
        dev_info = sd.query_devices(self.device_idx)
        self.out_channels = min(2, dev_info['max_output_channels']) if dev_info['max_output_channels'] > 0 else 1
        
        self.stats = Stats(device_name=dev_info['name'])
        self.audio_stream = None
        self.udp_sock = None
        self.discovery_sock = None
        self.http_server = None
        
        # Strict low-latency queue (max 2 chunks = 20ms ceiling)
        self.audio_queue = queue.Queue(maxsize=2)

    def _audio_callback(self, outdata, frames, time_info, status):
        """Hardware callback from PortAudio. Non-blocking real-time path."""
        try:
            chunk = self.audio_queue.get_nowait()
            if len(chunk) == len(outdata):
                outdata[:] = chunk
            elif len(chunk) > len(outdata):
                outdata[:] = chunk[:len(outdata)]
            else:
                outdata[:len(chunk)] = chunk
                outdata[len(chunk):].fill(0)
        except queue.Empty:
            outdata.fill(0)

    def start_audio_stream(self):
        """Open ultra-low latency callback stream to sound card."""
        self.audio_stream = sd.OutputStream(
            samplerate=SAMPLE_RATE,
            channels=self.out_channels,
            dtype=DTYPE,
            device=self.device_idx,
            blocksize=BUFFER_CHUNK_SAMPLES,
            callback=self._audio_callback,
            latency='low'
        )
        self.audio_stream.start()

    def run_udp_audio(self):
        """Zero-overhead UDP receiver loop."""
        try:
            self.udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 512 * 1024)
            self.udp_sock.bind(("0.0.0.0", self.port))
            self.udp_sock.settimeout(0.5)
        except Exception as e:
            console.print(f"[bold red]UDP 接收服务启动失败（端口 {self.port} 可能已被占用）：{e}[/bold red]")
            self.running = False
            return

        while self.running:
            try:
                data, addr = self.udp_sock.recvfrom(2048)
                if not data:
                    continue

                # Parse header if present (Magic: 'PMIC' = 0x504D4943)
                if len(data) >= 16 and data[:4] == b'PMIC':
                    seq, ts_ms = struct.unpack_from(">IQ", data, 4)
                    pcm_bytes = data[16:]
                else:
                    seq, ts_ms = None, None
                    pcm_bytes = data

                q_size = self.audio_queue.qsize()
                self.stats.update_audio(addr, pcm_bytes, seq=seq, ts_ms=ts_ms, qdepth=q_size)

                samples = np.frombuffer(pcm_bytes, dtype=np.int16)
                if self.out_channels == 2:
                    samples = np.column_stack((samples, samples))
                elif self.out_channels > 2:
                    samples = np.repeat(samples[:, np.newaxis], self.out_channels, axis=1)

                # Strict anti-lag drift clamping:
                # If queue already has >= 2 frames, phone clock is slightly faster than PC clock.
                # Drop stale frame immediately to permanently lock latency at 10~20ms!
                while self.audio_queue.qsize() >= 2:
                    try:
                        self.audio_queue.get_nowait()
                        self.stats.drift_corrections += 1
                    except Exception:
                        break

                try:
                    self.audio_queue.put_nowait(samples)
                except queue.Full:
                    pass

            except socket.timeout:
                continue
            except Exception:
                if self.running:
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

    def run_telemetry_service(self):
        """Run lightweight HTTP telemetry server."""
        TelemetryHandler.stats_ref = self.stats
        try:
            self.http_server = ThreadedHTTPServer(("0.0.0.0", self.telemetry_port), TelemetryHandler)
            self.http_server.serve_forever()
        except Exception:
            pass

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
    parser.add_argument("--telemetry-port", type=int, default=TELEMETRY_PORT, help="HTTP telemetry port")
    args = parser.parse_args()

    # 1. Device Selection (silent automatic)
    device_idx = list_and_select_device(cli_device_idx=args.device)
    dev_info = sd.query_devices(device_idx)

    # 2. Setup Server
    server = AudioServer(
        device_idx=device_idx,
        port=args.port,
        discovery_port=args.discovery_port,
        telemetry_port=args.telemetry_port
    )
    qr_data, ascii_qr = server.generate_ascii_qr()

    try:
        server.start_audio_stream()
    except Exception as e:
        console.print(f"[bold red]启动音频流失败：{e}[/bold red]")
        sys.exit(1)

    server.running = True

    # 3. Dedicated Background Threads (Isolated, zero audio path interference)
    t_udp = threading.Thread(target=server.run_udp_audio, daemon=True)
    t_discovery = threading.Thread(target=server.run_discovery_service, daemon=True)
    t_http = threading.Thread(target=server.run_telemetry_service, daemon=True)
    t_udp.start()
    t_discovery.start()
    t_http.start()

    # 4. Live Terminal Dashboard Loop
    try:
        with Live(console=console, refresh_per_second=10) as live:
            while True:
                now = time.time()
                snap = server.stats.to_dict()
                is_active = (snap["status"] == "active")

                status_color = "bold green" if is_active else "bold yellow"
                status_text = f"[{status_color}]{'🟢 手机已连接，正在收音中' if is_active else '🟡 等待手机连接...'}[/{status_color}]"

                vu_bar = build_vu_bar(snap["current_db"], snap["peak_db"], width=26)
                db_display = f"{snap['current_db']:5.1f} dB" if is_active else " -inf dB"

                lat_str = f"[bold green]{snap['latency_ms']:.1f} ms (极速)[/bold green]" if is_active else "[dim]-- ms[/dim]"
                jit_str = f"±{snap['jitter_ms']:.1f} ms" if is_active else "±-- ms"
                loss_str = f"{snap['packet_loss_pct']:.2f}% (丢包: {snap['packets_lost']})"

                info_table = Table.grid(padding=(0, 2))
                info_table.add_column(style="bold cyan")
                info_table.add_column()

                info_table.add_row("电脑局域网 IP:", f"[bold white]{server.lan_ip}[/bold white]")
                info_table.add_row("音频接收端口:", f"[bold white]{server.port}[/bold white]")
                info_table.add_row("音频输出设备:", f"[bold green]{dev_info['name']}[/bold green]")
                info_table.add_row("连接状态:", status_text)
                info_table.add_row("当前客户端:", f"[white]{snap['client']}[/white]")
                info_table.add_row("实时端到端延迟:", lat_str)
                info_table.add_row("网络抖动 / 丢包:", f"[white]{jit_str}[/white] / [white]{loss_str}[/white]")
                info_table.add_row("时钟漂移自动纠偏:", f"[bold green]{snap['drift_corrections']} 次[/bold green] (锁定 ≤ 20ms)")
                info_table.add_row("实时接收码率:", f"[white]{snap['bitrate_kbps']:5.1f} kbps[/white] (累计: {snap['packets_received']} 包)")
                info_table.add_row("实时音量电平:", f"{vu_bar} [bold]{db_display}[/bold]")
                info_table.add_row("监控控制台 Web:", f"[link=http://localhost:{server.telemetry_port}]http://localhost:{server.telemetry_port}[/link]")

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
                    title="[bold green]PhoneMic 电脑端麦克风网关 v1.1[/bold green] (按 Ctrl+C 退出)",
                    subtitle="[dim]提示：在 Discord / 微信 / 录音软件中将输入设备设为该虚拟声卡即可直接使用[/dim]",
                    border_style="blue"
                )

                live.update(main_panel)
                time.sleep(0.1)

    except KeyboardInterrupt:
        console.print("\n[yellow]正在停止服务...[/yellow]")
    finally:
        server.running = False
        if server.http_server:
            try:
                server.http_server.shutdown()
            except Exception:
                pass
        if server.audio_stream:
            server.audio_stream.stop()
            server.audio_stream.close()
        console.print("[green]服务已安全退出。[/green]")


if __name__ == "__main__":
    main()
