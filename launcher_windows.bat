@echo off
setlocal
:: ==============================================================================
:: GravitLauncher Windows Bootstrapper (JavaFX Ready)
:: This script automatically downloads Java with JavaFX and starts the launcher.
:: ==============================================================================

set PROJECT_NAME=NestWorld
set LAUNCHER_URL=https://launcher.nestworld.site/Launcher.jar
set JAVA_URL=https://download.bell-sw.com/java/17.0.14+10/bellsoft-jre17.0.14+10-windows-amd64-full.zip

set WORK_DIR=%APPDATA%\.%PROJECT_NAME%
set JRE_DIR=%WORK_DIR%\jre-17-full
set LAUNCHER_JAR=%WORK_DIR%\Launcher.jar

echo === %PROJECT_NAME% Launcher Bootstrapper ===

if not exist "%WORK_DIR%" mkdir "%WORK_DIR%"
cd /d "%WORK_DIR%"

:: 1. Download Launcher
if not exist "%LAUNCHER_JAR%" (
    echo [1/2] Downloading launcher...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('%LAUNCHER_URL%', '%LAUNCHER_JAR%')"
)

:: 2. Download JRE
if not exist "%JRE_DIR%" (
    echo [2/2] Downloading Java with JavaFX (BellSoft Liberica)...
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('%JAVA_URL%', 'jre.zip')"
    echo Extracting Java...
    powershell -Command "Expand-Archive -Path 'jre.zip' -DestinationPath 'jre_temp'"

    :: Move the inner folder to jre-17-full
    for /d %%i in (jre_temp\*) do move "%%i" "%JRE_DIR%"
    rmdir jre_temp
    del jre.zip
)

:: 3. Run Launcher
echo Starting launcher...
start "" "%JRE_DIR%\bin\javaw.exe" -Xmx1024M -jar "%LAUNCHER_JAR%"
