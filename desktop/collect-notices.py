#!/usr/bin/env python3
"""Copy Rust dependency notices for the desktop build's target platform."""
import json
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
output = ROOT / '.local/desktop/runtime/licenses/Rust'
metadata = json.loads(subprocess.check_output([
    'cargo', 'metadata', '--locked', '--format-version', '1',
    '--filter-platform', 'aarch64-apple-darwin',
    '--manifest-path', str(HERE / 'src-tauri/Cargo.toml'),
], text=True))
output.mkdir(parents=True, exist_ok=True)
manifest = []
for package in metadata['packages']:
    if package['source'] is None:
        continue
    source = Path(package['manifest_path']).parent
    destination = output / f"{package['name']}-{package['version']}"
    destination.mkdir(exist_ok=True)
    notices = []
    for path in source.iterdir():
        if path.name.upper().startswith(('LICEN', 'COPYING', 'NOTICE', 'UNLICENSE', 'README')):
            if path.is_dir():
                shutil.copytree(path, destination / path.name, dirs_exist_ok=True)
            else:
                shutil.copy2(path, destination / path.name)
            notices.append(path.name)
    manifest.append({
        'name': package['name'], 'version': package['version'],
        'license': package['license'], 'repository': package['repository'], 'notices': notices,
    })
(output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f'Collected license metadata for {len(manifest)} Rust dependencies.')
