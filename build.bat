@echo off
setlocal

REM Build GeoVelis SOI
REM Ensure ArcGIS Enterprise SDK Maven artifacts are installed first:
REM   cd "%ENTDEVKITJAVA%"
REM   install-maven-artifacts.bat

mvn clean package

if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

echo Build completed. Check target\*.soe
endlocal
