@echo off
chcp 65001 >nul
title PhoneMic PC Microphone Server
cd /d "%~dp0server-pc"
.venv\Scripts\python.exe server.py --lang en
pause
