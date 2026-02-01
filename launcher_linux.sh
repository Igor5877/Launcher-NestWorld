#!/bin/bash

# ==============================================================================
# GravitLauncher Linux Bootstrapper
# Цей скрипт автоматично завантажує Java (якщо потрібно) та запускає лаунчер.
# ==============================================================================

# Налаштування
PROJECT_NAME="NestWorld"
LAUNCHER_URL="https://launcher.nestworld.site/Launcher.jar"
JAVA_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.10%2B7/OpenJDK17U-jre_x64_linux_hotspot_17.0.10_7.tar.gz"

# Папки та файли
WORK_DIR="$HOME/.${PROJECT_NAME,,}"
JRE_DIR="$WORK_DIR/jre-17"
LAUNCHER_JAR="$WORK_DIR/Launcher.jar"

echo "=== $PROJECT_NAME Launcher Bootstrapper ==="

# Створення робочої директорії
mkdir -p "$WORK_DIR"
cd "$WORK_DIR" || exit

# Перевірка наявності curl
if ! command -v curl &> /dev/null; then
    echo "Помилка: curl не встановлено. Будь ласка, встановіть його (sudo apt install curl)."
    exit 1
fi

# Завантаження лаунчера, якщо його немає
if [ ! -f "$LAUNCHER_JAR" ]; then
    echo "[1/3] Завантаження лаунчера..."
    curl -L -o "$LAUNCHER_JAR" "$LAUNCHER_URL"
fi

# Перевірка Java
USE_SYSTEM_JAVA=false
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -n 1 | cut -d '"' -f 2 | cut -d '.' -f 1)
    if [ "$JAVA_VER" -eq 17 ]; then
        echo "[2/3] Знайдено системну Java 17."
        USE_SYSTEM_JAVA=true
    fi
fi

if [ "$USE_SYSTEM_JAVA" = false ]; then
    if [ ! -d "$JRE_DIR" ]; then
        echo "[2/3] Портативна Java не знайдена. Завантаження JRE 17..."
        curl -L -o jre.tar.gz "$JAVA_URL"
        mkdir -p "$JRE_DIR"
        tar -xzf jre.tar.gz -C "$JRE_DIR" --strip-components=1
        rm jre.tar.gz
        echo "JRE встановлено у $JRE_DIR"
    else
        echo "[2/3] Використання портативної Java з $JRE_DIR"
    fi
    JAVA_BIN="$JRE_DIR/bin/java"
else
    JAVA_BIN="java"
fi

# Запуск лаунчера
echo "[3/3] Запуск лаунчера..."
"$JAVA_BIN" -Xmx512M -jar "$LAUNCHER_JAR"
