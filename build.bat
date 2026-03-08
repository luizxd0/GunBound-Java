@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "MVN_CMD="
for /f "delims=" %%I in ('where mvn 2^>nul') do (
    set "MVN_CMD=%%~fI"
    goto :mvn_found
)
if exist "C:\apache-maven-3.9.12\bin\mvn.cmd" (
    set "MVN_CMD=C:\apache-maven-3.9.12\bin\mvn.cmd"
)
:mvn_found

if not defined MVN_CMD (
    call :fail "Maven not found. Install Maven or add it to PATH."
    exit /b 1
)

set "BUILD_GOALS=package"
if /I "%~1"=="clean" set "BUILD_GOALS=clean package"

echo [build] Running: %MVN_CMD% -DskipTests %BUILD_GOALS%
call "%MVN_CMD%" -DskipTests %BUILD_GOALS%
if errorlevel 1 (
    call :fail "Build failed."
    exit /b 1
)

set "JAR_PATH=%~dp0target\GunBoundJavaEmulator-1.0-SNAPSHOT-jar-with-dependencies.jar"
echo [build] SUCCESS.
if exist "%JAR_PATH%" echo [build] Artifact: "%JAR_PATH%"
exit /b 0

:fail
echo [build] ERROR: %~1
echo [build] Press any key to continue...
pause >nul
exit /b 1
