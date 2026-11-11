@echo off
setlocal EnableExtensions

REM =====================================================================
REM  AI Application Development Demo - launcher
REM  (kept ASCII-only on purpose: cmd.exe mis-parses UTF-8 batch files)
REM =====================================================================

cd /d "%~dp0"

REM --- 1. JDK 21 -------------------------------------------------------
REM Spring AI 1.1 requires Java 17+. Change the path below if needed.
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.10"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JAVA_HOME not found: %JAVA_HOME%
    echo         Please install JDK 17 or later, or edit JAVA_HOME in run.bat
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM --- 2. API Key ------------------------------------------------------
REM Preferred: set DASHSCOPE_API_KEY as a system/user environment variable.
REM Otherwise paste your key between the quotes below (do NOT commit it).
if not defined DASHSCOPE_API_KEY set "DASHSCOPE_API_KEY="
if "%DASHSCOPE_API_KEY%"=="" (
    echo [WARN] DASHSCOPE_API_KEY is empty.
    echo        The app will still start, but every AI call will fail with 401.
    echo        Set it now, or edit application.yml, then restart.
    echo.
)

REM --- 3. Build if needed ---------------------------------------------
if not exist "target\ai-ops-assistant.jar" (
    echo [1/2] Building ^(first run only^) ...
    call mvn -B -DskipTests package
    if errorlevel 1 (
        echo.
        echo [ERROR] Build failed. See the Maven output above.
        pause
        exit /b 1
    )
) else (
    echo [1/2] Found target\ai-ops-assistant.jar - skipping build.
    echo       To rebuild: mvn clean package
)

REM --- 4. Run ----------------------------------------------------------
echo [2/2] Starting on http://localhost:8088/  ^(Ctrl+C to stop^)
echo.
java -jar target\ai-ops-assistant.jar

endlocal
