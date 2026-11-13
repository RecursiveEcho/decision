#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
config=()
if [[ "${1:-}" == "--product-preview" ]]; then
    config=(--config "$root/desktop/tauri-product-preview.conf.json")
elif [[ $# -ne 0 ]]; then
    echo "Usage: desktop/build.sh [--product-preview]" >&2
    exit 2
fi
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
# The JRE includes read-only resources. Tauri copies them into its build cache;
# make only those disposable copies replaceable on the next build.
for cache in src-tauri/target/debug/runtime src-tauri/target/release/runtime; do
    if [[ -d "$cache" ]]; then chmod -R u+w "$cache"; fi
done
npm run build -- --ci --no-bundle "${config[@]}" -- --locked
python3 collect-notices.py
npm exec -- tauri bundle --bundles app --ci "${config[@]}"
