#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
backend="$root/backend/target/decision-backend-0.1.0.jar"
output="${1:?Pass the absolute output path for the desktop adapter jar}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q "$backend" 'BOOT-INF/*' -d "$work"
mkdir "$work/classes"
javac --release 17 -classpath "$work/BOOT-INF/classes:$work/BOOT-INF/lib/*" \
    -d "$work/classes" "$root/desktop/java/dev/decision/DesktopConfiguration.java"
jar --create --file "$output" -C "$work/classes" .
