@echo off
rem Shared prerequisite bootstrap. Deliberately no SETLOCAL: selected tool paths must return to the calling script.
if "%~1"=="" goto invalid_mode
if /I "%~1"=="Run" goto mode_ok
if /I "%~1"=="Build" goto mode_ok
if /I "%~1"=="Test" goto mode_ok
if /I "%~1"=="Package" goto mode_ok
if /I "%~1"=="Validate" goto mode_ok
goto invalid_mode

:mode_ok
where powershell.exe >nul 2>nul
if errorlevel 1 (
  echo [IBC Manager] Windows PowerShell is required to check and install prerequisites.
  echo [IBC Manager] PowerShell is included with supported Windows 10 and Windows 11 installations.
  exit /b 2
)

set "IBC_MANAGER_BOOTSTRAP_ENV=%TEMP%\IBCManager-Prerequisites-%RANDOM%-%RANDOM%.cmd"
if exist "%IBC_MANAGER_BOOTSTRAP_ENV%" del /f /q "%IBC_MANAGER_BOOTSTRAP_ENV%" >nul 2>nul

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0ensure-prerequisites.ps1" -Mode "%~1" -EnvironmentFile "%IBC_MANAGER_BOOTSTRAP_ENV%"
set "IBC_MANAGER_BOOTSTRAP_RESULT=%ERRORLEVEL%"
if not "%IBC_MANAGER_BOOTSTRAP_RESULT%"=="0" goto bootstrap_failed
if not exist "%IBC_MANAGER_BOOTSTRAP_ENV%" (
  echo [IBC Manager] Prerequisite setup did not produce its environment file.
  set "IBC_MANAGER_BOOTSTRAP_RESULT=2"
  goto bootstrap_failed
)

call "%IBC_MANAGER_BOOTSTRAP_ENV%"
set "IBC_MANAGER_BOOTSTRAP_RESULT=%ERRORLEVEL%"
del /f /q "%IBC_MANAGER_BOOTSTRAP_ENV%" >nul 2>nul
set "IBC_MANAGER_BOOTSTRAP_ENV="
if not "%IBC_MANAGER_BOOTSTRAP_RESULT%"=="0" (
  echo [IBC Manager] Could not import the verified prerequisite paths.
  exit /b %IBC_MANAGER_BOOTSTRAP_RESULT%
)
set "IBC_MANAGER_BOOTSTRAP_RESULT="
exit /b 0

:bootstrap_failed
if exist "%IBC_MANAGER_BOOTSTRAP_ENV%" del /f /q "%IBC_MANAGER_BOOTSTRAP_ENV%" >nul 2>nul
set "IBC_MANAGER_BOOTSTRAP_ENV="
exit /b %IBC_MANAGER_BOOTSTRAP_RESULT%

:invalid_mode
echo [IBC Manager] Internal error: bootstrap mode must be Run, Build, Test, Package, or Validate.
exit /b 2
