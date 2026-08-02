@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0.."

set "JAR=dist\IBC-Manager-1.0.8.jar"
if not exist "%JAR%" set "JAR=IBC-Manager-1.0.8.jar"

if exist "%JAR%" goto bootstrap_runtime

if not exist "build.xml" goto jar_missing
if not exist "src\main\java" goto jar_missing
set "BUILD_DRIVER=src\build\java\io\github\ibcmanager\build\BuildProject.java"
if not exist "%BUILD_DRIVER%" goto jar_missing

echo [IBC Manager] The runnable JAR is not present in this source tree.
echo [IBC Manager] IBC Manager will now verify the JDK and build the JAR without Apache Ant.
call "%~dp0bootstrap.bat" Build
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" exit /b %RESULT%

"%IBC_MANAGER_JAVA_EXE%" -Dfile.encoding=UTF-8 "%BUILD_DRIVER%" self-test clean jar smoke
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
  echo [IBC Manager] The source build failed. Review the messages above.
  exit /b %RESULT%
)
set "JAR=dist\IBC-Manager-1.0.8.jar"
if not exist "%JAR%" (
  echo [IBC Manager] The build completed without creating %JAR%.
  exit /b 2
)
goto verify_jar

:bootstrap_runtime
call "%~dp0bootstrap.bat" Run
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" exit /b %RESULT%

:verify_jar
"%IBC_MANAGER_JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" --version >nul
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
  echo [IBC Manager] The Java runtime could not load %JAR%.
  echo [IBC Manager] Review the Java error above or rebuild from the source archive.
  exit /b %RESULT%
)

start "IBC Manager" "%IBC_MANAGER_JAVAW_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" %*
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
  echo [IBC Manager] Windows could not start IBC Manager.
  exit /b %RESULT%
)
exit /b 0

:jar_missing
echo [IBC Manager] IBC-Manager-1.0.8.jar or its source build driver was not found.
echo [IBC Manager] Extract the complete release archive, or run this launcher from the complete source tree.
exit /b 2
