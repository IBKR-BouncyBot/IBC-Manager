@echo off
setlocal EnableExtensions DisableDelayedExpansion
call "%~dp0scripts\test.bat" %*
exit /b %ERRORLEVEL%
