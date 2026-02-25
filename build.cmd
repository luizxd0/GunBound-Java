@echo off
title Build GunBound Server
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot
set PATH=%JAVA_HOME%\bin;C:\apache-maven-3.9.12\bin;%PATH%
cd /d "%~dp0"

echo Building...
call mvn clean install
echo.
pause
