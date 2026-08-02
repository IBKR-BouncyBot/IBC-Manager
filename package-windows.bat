@echo off
setlocal EnableExtensions DisableDelayedExpansion
call "%~dp0scripts\package-windows.bat" %*
exit /b %ERRORLEVEL%
