@echo off
rem Starts SeryMusic web on this computer: http://localhost:8080
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 (
    echo Python is not installed. Get it from https://www.python.org/downloads/
    echo and tick "Add python.exe to PATH" during the installation.
    pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo Creating virtual environment...
    python -m venv .venv
    if errorlevel 1 goto :failed
)

echo Installing / updating dependencies...
".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -q -r requirements.txt
if errorlevel 1 goto :failed
rem YouTube changes often, the latest yt-dlp keeps songs playing
".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -q -U "yt-dlp[default]"

if not defined SERYMUSIC_PORT set SERYMUSIC_PORT=8080
rem Meant for the home network, no password. Set SERYMUSIC_PASSWORD to require one.
if not defined SERYMUSIC_PASSWORD set SERYMUSIC_NO_PASSWORD=1

rem Opens the browser once the server had time to start
start "" /b cmd /c "ping -n 5 127.0.0.1 >nul & start http://localhost:%SERYMUSIC_PORT%"

echo.
echo Close this window or press Ctrl+C to stop SeryMusic web.
".venv\Scripts\python.exe" app.py
goto :end

:failed
echo.
echo Something went wrong, see the messages above.

:end
pause
