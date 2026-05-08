@echo off
setlocal

chcp 65001 >nul
title SJTU Agent Local Chat

cd /d "%~dp0"

set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
set "SJTU_AGENT_EXE=%~dp0.venv\Scripts\sjtu-agent.exe"

if not exist "%SJTU_AGENT_EXE%" (
    echo [sjtu-agent] Cannot find "%SJTU_AGENT_EXE%".
    echo [sjtu-agent] Run this first:
    echo     powershell -ExecutionPolicy Bypass -File "%~dp0install.ps1"
    if /i not "%SJTU_AGENT_NO_PAUSE%"=="1" pause
    exit /b 1
)

echo [sjtu-agent] Starting local terminal chat...
echo [sjtu-agent] Type quit to exit.
echo.

"%SJTU_AGENT_EXE%" chat
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo [sjtu-agent] Local chat exited with code %EXIT_CODE%.
)

if /i not "%SJTU_AGENT_NO_PAUSE%"=="1" pause
exit /b %EXIT_CODE%
