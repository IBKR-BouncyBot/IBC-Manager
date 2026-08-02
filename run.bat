@echo off
setlocal EnableExtensions DisableDelayedExpansion
call "%~dp0scripts\run.bat" %*
exit /b %ERRORLEVEL%
