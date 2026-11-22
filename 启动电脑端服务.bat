@echo off
chcp 65001 >nul
title PhoneMic 电脑麦克风服务
cd /d "%~dp0server-pc"
.venv\Scripts\python.exe server.py
pause
