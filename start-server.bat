@echo off
setlocal EnableExtensions
cd /d "%~dp0"

if /I "%~1"=="/?" goto :usage
if /I "%~1"=="-h" goto :usage
if /I "%~1"=="--help" goto :usage

set "DO_BUILD=0"
if /I "%~1"=="--build" set "DO_BUILD=1"
if not "%~1"=="" if /I not "%~1"=="--build" goto :usage_error

set "JAR_PATH=%~dp0target\GunBoundJavaEmulator-1.0-SNAPSHOT-jar-with-dependencies.jar"

if "%DO_BUILD%"=="1" (
    call "%~dp0build.bat"
    if errorlevel 1 (
        call :fail "Build step failed."
        exit /b 1
    )
)

if not exist "%JAR_PATH%" (
    call :fail "Build artifact not found. Run build.bat first, or use start-server.bat --build."
    exit /b 1
)

set "JAVA_CMD="
for /f "delims=" %%I in ('where java 2^>nul') do (
    set "JAVA_CMD=%%~fI"
    goto :java_found
)
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)
:java_found

if not defined JAVA_CMD (
    call :fail "Java not found. Install Java 11+ or set JAVA_HOME."
    exit /b 1
)

echo [start] Starting GunBound server...
echo [start] Press Ctrl+C to stop.
"%JAVA_CMD%" -cp "%JAR_PATH%" br.com.gunbound.emulator.GunBoundStarter
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" echo [start] Server exited with code %EXIT_CODE%.
exit /b %EXIT_CODE%

:usage
echo Usage:
echo   start-server.bat          ^(starts server from existing jar^)
echo   start-server.bat --build  ^(builds, then starts server^)
exit /b 0

:usage_error
echo [start] ERROR: Unknown option "%~1"
echo.
goto :usage

:fail
echo [start] ERROR: %~1
echo [start] Press any key to continue...
pause >nul
exit /b 1
