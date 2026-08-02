@echo off
setlocal EnableExtensions DisableDelayedExpansion
call "%~dp0scripts\validate-windows.bat" %*
exit /b %ERRORLEVEL%
