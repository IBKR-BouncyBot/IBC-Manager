@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0.."

call "%~dp0bootstrap.bat" Validate
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" exit /b %RESULT%

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0ensure-prerequisites.ps1" -SelfTest
if errorlevel 1 exit /b 1

set "BUILD_DRIVER=src\build\java\io\github\ibcmanager\build\BuildProject.java"
if not exist "%BUILD_DRIVER%" (
  echo [IBC Manager] The JDK-native build driver was not found: %BUILD_DRIVER%
  exit /b 2
)
"%IBC_MANAGER_JAVA_EXE%" -Dfile.encoding=UTF-8 "%BUILD_DRIVER%" self-test clean test jar smoke gui-smoke
if errorlevel 1 exit /b 1

"%IBC_MANAGER_JAVA_EXE%" -jar "dist\IBC-Manager-1.0.19.jar" --version
if errorlevel 1 exit /b 1

set "SMOKE=%TEMP%\IBCManager-Smoke-%RANDOM%-%RANDOM%"
"%IBC_MANAGER_JAVA_EXE%" -jar "dist\IBC-Manager-1.0.19.jar" --headless-smoke --data-dir "%SMOKE%"
set "RESULT=%ERRORLEVEL%"
rmdir /s /q "%SMOKE%" >nul 2>nul
if not "%RESULT%"=="0" exit /b %RESULT%

echo Automated Windows validation passed.
echo Complete docs\WINDOWS_VALIDATION_CHECKLIST.md with paper IBC and IB Gateway.
exit /b 0
