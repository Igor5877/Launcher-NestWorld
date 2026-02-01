#!/bin/bash

# ==============================================================================
# GravitLauncher Linux Bootstrapper (JavaFX Ready)
# This script automatically downloads Java with JavaFX and starts the launcher.
# ==============================================================================

# Configuration
PROJECT_NAME="NestWorld"
LAUNCHER_URL="https://launcher.nestworld.site/Launcher.jar"
# BellSoft Liberica Full JRE (includes JavaFX)
JAVA_URL="https://download.bell-sw.com/java/17.0.14+10/bellsoft-jre17.0.14+10-linux-amd64-full.tar.gz"

# Directories and files
WORK_DIR="$HOME/.${PROJECT_NAME,,}"
JRE_DIR="$WORK_DIR/jre-17-full"
LAUNCHER_JAR="$WORK_DIR/Launcher.jar"

echo "=== $PROJECT_NAME Launcher Bootstrapper ==="

# Create work directory
mkdir -p "$WORK_DIR"
cd "$WORK_DIR" || exit

# Check for curl
if ! command -v curl &> /dev/null; then
    echo "Error: curl is not installed. Please install it (e.g., sudo apt install curl)."
    exit 1
fi

# Download launcher if missing or update it?
# For now, let's just download if missing.
if [ ! -f "$LAUNCHER_JAR" ]; then
    echo "[1/2] Downloading launcher..."
    curl -L -o "$LAUNCHER_JAR" "$LAUNCHER_URL"
fi

# We ALWAYS prefer our portable Full JRE to avoid JavaFX issues with system Java
if [ ! -d "$JRE_DIR" ]; then
    echo "[2/2] Downloading Java with JavaFX (BellSoft Liberica)..."
    curl -L -o jre.tar.gz "$JAVA_URL"
    mkdir -p "$JRE_DIR"
    tar -xzf jre.tar.gz -C "$JRE_DIR" --strip-components=1
    rm jre.tar.gz
    echo "JRE installed in $JRE_DIR"
else
    echo "[2/2] Using portable Java with JavaFX support."
fi

JAVA_BIN="$JRE_DIR/bin/java"

# Start launcher
echo "Starting launcher..."
"$JAVA_BIN" -Xmx1024M -jar "$LAUNCHER_JAR"
