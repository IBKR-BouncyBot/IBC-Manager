@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0.."

call "%~dp0bootstrap.bat" Build
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" exit /b %RESULT%

set "BUILD_DRIVER=src\build\java\io\github\ibcmanager\build\BuildProject.java"
if not exist "%BUILD_DRIVER%" (
  echo [IBC Manager] The JDK-native build driver was not found: %BUILD_DRIVER%
  exit /b 2
)
"%IBC_MANAGER_JAVA_EXE%" -Dfile.encoding=UTF-8 "%BUILD_DRIVER%" self-test clean test jar smoke dist
exit /b %ERRORLEVEL%
