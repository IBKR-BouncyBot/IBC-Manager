@echo off
rem User-facing console completion only. Never call from a managed Gateway wrapper.
setlocal EnableExtensions DisableDelayedExpansion
set "IBC_MANAGER_PAUSE_RESULT=%~1"
if not defined IBC_MANAGER_PAUSE_RESULT set "IBC_MANAGER_PAUSE_RESULT=1"
rem Explicit automation opt-out and hosted CI must never wait for a key.
if "%IBC_MANAGER_NO_PAUSE%"=="1" exit /b %IBC_MANAGER_PAUSE_RESULT%
if defined CI exit /b %IBC_MANAGER_PAUSE_RESULT%
echo.
echo [IBC Manager] Finished with exit code %IBC_MANAGER_PAUSE_RESULT%.
echo [IBC Manager] Review the output above before closing this window.
pause
exit /b %IBC_MANAGER_PAUSE_RESULT%
