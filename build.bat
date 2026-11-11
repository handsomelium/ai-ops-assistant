@echo off
setlocal EnableExtensions

REM Clean build + run the test suite. ASCII-only on purpose.

cd /d "%~dp0"

if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.10"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JAVA_HOME not found: %JAVA_HOME%
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo JAVA_HOME = %JAVA_HOME%
java -version
echo.

call mvn -B clean verify %*
set RC=%ERRORLEVEL%

echo.
if %RC%==0 (echo BUILD + TESTS OK) else (echo BUILD FAILED, exit code %RC%)
pause
exit /b %RC%
