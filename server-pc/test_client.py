import socket
import time
import numpy as np

# Send 1 second of 440Hz sine wave PCM to test PC audio receiver
UDP_IP = "127.0.0.1"
UDP_PORT = 9000
SAMPLE_RATE = 48000
DURATION = 1.0  # 1 second test tone
FREQUENCY = 440.0

t = np.linspace(0, DURATION, int(SAMPLE_RATE * DURATION), endpoint=False)
waveform = (np.sin(2 * np.pi * FREQUENCY * t) * 16000).astype(np.int16)
data_bytes = waveform.tobytes()

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
chunk_size = 960  # 10ms chunks
for i in range(0, len(data_bytes), chunk_size):
    chunk = data_bytes[i:i+chunk_size]
    sock.sendto(chunk, (UDP_IP, UDP_PORT))
    time.sleep(0.01)

print("Test audio stream sent successfully!")
