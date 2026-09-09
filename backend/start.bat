@echo off
setlocal
cd /d "%~dp0"

if not exist .venv (
    py -m venv .venv
)

call .venv\Scripts\activate.bat
python -m pip install -r requirements.txt

if not exist .env (
    copy .env.example .env >nul
    echo Created backend\.env. Put your OPENAI_API_KEY there, then run this file again.
    pause
    exit /b 1
)

python -m uvicorn main:app --host 127.0.0.1 --port 8787
