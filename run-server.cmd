@echo off
title GunBound Thor's Hammer Server
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot
set PATH=%JAVA_HOME%\bin;C:\apache-maven-3.9.12\bin;%PATH%
cd /d "%~dp0"

echo Starting GunBound Server...
echo.
mvn exec:java -Dexec.mainClass=br.com.gunbound.emulator.GunBoundStarter

pause
