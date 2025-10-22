#!/bin/bash
# run-copy.command - macOS launcher for the FileCopy application
# Double-clickable in Finder or runnable from a terminal.

set -euo pipefail
IFS=$'\n\t'

usage() {
  echo "Usage: $0 [--gui] [sourcePath targetPath [threads] [max-heap]]"
  echo "  --gui        - Start graphical user interface"
  echo "  sourcePath   - file or directory to copy"
  echo "  targetPath   - destination path"
  echo "  threads      - optional, number of worker threads (default: cpu-1, min 1)"
  echo "  max-heap     - optional, JVM max heap (e.g. 4096m). If omitted, auto-detected to ~50% RAM capped to 8G"
  exit 2
}

# GUI-Modus wenn ohne Parameter oder mit --gui aufgerufen
if [[ ${#} -eq 0 ]] || [[ "$1" == "--gui" ]]; then
    GUI_MODE=true
    shift || true
else
    GUI_MODE=false
fi

# Im GUI-Modus keine weiteren Parameter notwendig
if [[ "$GUI_MODE" == "true" ]] && [[ ${#} -gt 0 ]]; then
    usage
fi

# Im CLI-Modus mindestens zwei Parameter erforderlich
if [[ "$GUI_MODE" == "false" ]] && [[ ${#} -lt 2 ]]; then
    usage
fi

# CLI Parameter verarbeiten
if [[ "$GUI_MODE" == "false" ]]; then
    SRC="$1"
    TGT="$2"
    THREADS="${3-}"
    MAX_HEAP="${4-}"
else
    SRC=""
    TGT=""
    THREADS=""
    MAX_HEAP=""
fi

# Detect CPU cores on macOS in a safe way
if [[ -z "$THREADS" ]]; then
  if command -v sysctl >/dev/null 2>&1; then
    CPUS=$(sysctl -n hw.ncpu || echo 2)
  else
    CPUS=2
  fi
  if [[ "$CPUS" -gt 1 ]]; then
    THREADS=$((CPUS - 1))
  else
    THREADS=1
  fi
fi

# Detect available memory and pick a safe Xmx default (50% capped at 8192 MB)
if [[ -z "$MAX_HEAP" ]]; then
  if command -v sysctl >/dev/null 2>&1; then
    MEM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo 8589934592)
    HALF_MB=$(( (MEM_BYTES / 1024 / 1024) / 2 ))
    if [[ $HALF_MB -gt 8192 ]]; then
      HALF_MB=8192
    fi
    if [[ $HALF_MB -lt 256 ]]; then
      HALF_MB=256
    fi
    MAX_HEAP="${HALF_MB}m"
  else
    MAX_HEAP="2048m"
  fi
fi

# JVM options tuned for Java 17: stability and throughput
JVM_OPTS=(
  "-Xms512m"
  "-Xmx${MAX_HEAP}"
  "-XX:+UseG1GC"
  "-XX:MaxGCPauseMillis=200"
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-XX:HeapDumpPath=./heapdump.hprof"
  "-Dfile.encoding=UTF-8"
)

# Location of the built jar - adjust if you use a different Gradle/packaging setup
# Try likely locations relative to repo root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${SCRIPT_DIR}/build/libs/foo.jar"
if [[ ! -f "$JAR" ]]; then
  JAR="${SCRIPT_DIR}/build/libs/*.jar"
  # glob may expand to the correct artifact; keep as-is and let java fail if not found
fi

if ! compgen -G "$JAR" >/dev/null; then
  echo "Cannot find jar at ${SCRIPT_DIR}/build/libs/. Please build the project first (e.g. ./gradlew assemble)." >&2
  exit 1
fi

if [[ "$GUI_MODE" == "true" ]]; then
    echo "Starting FileCopy GUI..."
    exec java "${JVM_OPTS[@]}" -jar "$JAR"
else
    echo "Starting FileCopy CLI: src='${SRC}' -> target='${TGT}' threads=${THREADS} JVM_Xmx=${MAX_HEAP}"
    exec java "${JVM_OPTS[@]}" -jar "$JAR" "$SRC" "$TGT" "$THREADS"
fi
