#!/usr/bin/env python3
"""Check packaged native code for arm64, signatures and local library dependencies."""
import argparse
from pathlib import Path
import subprocess

MACH = {b'\xcf\xfa\xed\xfe', b'\xce\xfa\xed\xfe', b'\xca\xfe\xba\xbe', b'\xca\xfe\xba\xbf'}
SYSTEM = ('/usr/lib/', '/System/Library/')


def output(*args):
    return subprocess.check_output(args, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runtime', type=Path)
    args = parser.parse_args()
    root = args.runtime.resolve(strict=True)
    errors = []
    count = 0
    for path in root.rglob('*'):
        if path.is_symlink():
            if not path.resolve().is_relative_to(root) or not path.exists():
                errors.append(f'Invalid resource link: {path.relative_to(root)}')
            continue
        if not path.is_file():
            continue
        with path.open('rb') as source:
            magic = source.read(4)
        relative = path.relative_to(root)
        if magic == b'\x7fELF':
            errors.append(f'Foreign native code: {relative}')
            continue
        if magic not in MACH or path.suffix == '.a':
            continue
        count += 1
        if 'arm64' not in output('lipo', '-archs', str(path)).split():
            errors.append(f'Missing arm64: {relative}')
        signed = subprocess.run(['codesign', '--verify', '--strict', str(path)],
                                capture_output=True)
        if signed.returncode:
            errors.append(f'Invalid nested signature: {relative}')
        # A dylib's own LC_ID_DYLIB can retain its upstream install path.
        identities = {line.strip() for line in output('otool', '-D', str(path)).splitlines()[1:]
                      if line.startswith('\t') or line.startswith('/')}
        for line in output('otool', '-L', str(path)).splitlines():
            if not line.startswith('\t'):
                continue
            dependency = line.strip().split(' (')[0]
            if dependency in identities:
                continue
            if dependency.startswith('/') and not dependency.startswith(SYSTEM):
                errors.append(f'External library: {relative} -> {dependency}')
            if dependency.startswith('@loader_path/'):
                target = (path.parent / dependency.removeprefix('@loader_path/')).resolve()
                if not target.is_relative_to(root) or not target.is_file():
                    errors.append(f'Missing bundled library: {relative} -> {dependency}')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'PASS: {count} native files contain arm64 and have valid signatures; '
          'resource links and library references passed.')


if __name__ == '__main__':
    main()
