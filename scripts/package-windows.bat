@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem Every user-facing exit returns here, including prerequisite and build failures.
call :main %*
set "IBC_MANAGER_ENTRY_RESULT=%ERRORLEVEL%"
call "%~dp0pause-after-run.bat" "%IBC_MANAGER_ENTRY_RESULT%"
exit /b %IBC_MANAGER_ENTRY_RESULT%

:main
cd /d "%~dp0.."
if errorlevel 1 exit /b 2

call "%~dp0bootstrap.bat" Package
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" exit /b %RESULT%

set "BUILD_DRIVER=src\build\java\io\github\ibcmanager\build\BuildProject.java"
if not exist "%BUILD_DRIVER%" (
  echo [IBC Manager] The JDK-native build driver was not found: %BUILD_DRIVER%
  exit /b 2
)
"%IBC_MANAGER_JAVA_EXE%" -Dfile.encoding=UTF-8 "%BUILD_DRIVER%" self-test clean test jar smoke gui-smoke dist
if errorlevel 1 exit /b 1

set "INPUT=build\jpackage-input"
set "DEST=dist\windows"
set "JLINK_OPTIONS=--strip-debug --no-man-pages --no-header-files"
if exist "%INPUT%" rmdir /s /q "%INPUT%"
if exist "%DEST%" rmdir /s /q "%DEST%"
mkdir "%INPUT%" || exit /b 1
mkdir "%DEST%" || exit /b 1
copy /y "dist\IBC-Manager-2.0.3.jar" "%INPUT%\" >nul || exit /b 1

"%IBC_MANAGER_JPACKAGE_EXE%" ^
  --type app-image ^
  --name "IBC Manager" ^
  --app-version 2.0.3 ^
  --vendor "IBC Manager contributors" ^
  --description "IB Gateway manager with an integrated IBC engine" ^
  --input "%INPUT%" ^
  --main-jar "IBC-Manager-2.0.3.jar" ^
  --main-class io.github.ibcmanager.app.IbcManagerApp ^
  --dest "%DEST%" ^
  --jlink-options "%JLINK_OPTIONS%" ^
  --java-options "-Dfile.encoding=UTF-8"
if errorlevel 1 exit /b 1
if not exist "%DEST%\IBC Manager\IBC Manager.exe" (
  echo [IBC Manager] jpackage reported success but the application image was not created.
  exit /b 1
)
if not exist "%DEST%\IBC Manager\runtime\bin\java.exe" (
  echo [IBC Manager] The portable application runtime is incomplete: runtime\bin\java.exe is missing.
  echo [IBC Manager] The package must retain Java native commands for the detached process-log relay.
  exit /b 1
)
for %%F in ("%DEST%\IBC Manager\runtime\bin\java.exe") do if %%~zF LEQ 0 (
  echo [IBC Manager] The portable Java launcher is empty: %%~fF
  exit /b 1
)

"%IBC_MANAGER_JPACKAGE_EXE%" ^
  --type exe ^
  --name "IBC Manager" ^
  --app-version 2.0.3 ^
  --vendor "IBC Manager contributors" ^
  --description "IB Gateway manager with an integrated IBC engine" ^
  --input "%INPUT%" ^
  --main-jar "IBC-Manager-2.0.3.jar" ^
  --main-class io.github.ibcmanager.app.IbcManagerApp ^
  --dest "%DEST%" ^
  --win-menu ^
  --win-shortcut ^
  --win-dir-chooser ^
  --jlink-options "%JLINK_OPTIONS%" ^
  --java-options "-Dfile.encoding=UTF-8"
if errorlevel 1 (
  echo [IBC Manager] The application image succeeded, but EXE installer creation failed.
  echo [IBC Manager] Review the jpackage/WiX output above and rerun this script.
  exit /b 1
)

"%IBC_MANAGER_JAVA_EXE%" -Dfile.encoding=UTF-8 "%BUILD_DRIVER%" windows-release-zip
if errorlevel 1 (
  echo [IBC Manager] The Windows installer was created, but the Windows release ZIP could not be assembled.
  exit /b 1
)
set "WINDOWS_RELEASE_ZIP=dist\IBC_Manager_2.0.3_Release_windows.zip"
if not exist "%WINDOWS_RELEASE_ZIP%" (
  echo [IBC Manager] Windows release ZIP creation reported success, but the archive was not found:
  echo [IBC Manager]   %WINDOWS_RELEASE_ZIP%
  exit /b 1
)

echo [IBC Manager] Windows packages created under %DEST%.
echo [IBC Manager] Windows release ZIP created at %WINDOWS_RELEASE_ZIP%.
exit /b 0
