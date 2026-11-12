#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/backend"
./mvnw -B -DskipTests package
cd "$root/web"
npm ci --no-audit --no-fund
npm run build
cd "$root"
python3 desktop/prepare-runtime.py
python3 desktop/audit-runtime.py .local/desktop/runtime
cd "$root/desktop"
npm ci --no-audit --no-fund
npm run build -- --ci --no-bundle -- --locked
python3 collect-notices.py
npm exec -- tauri bundle --bundles app --ci
