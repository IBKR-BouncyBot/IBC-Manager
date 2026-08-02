@echo off
setlocal EnableExtensions DisableDelayedExpansion
call "%~dp0scripts\build.bat" %*
exit /b %ERRORLEVEL%
