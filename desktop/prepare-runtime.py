#!/usr/bin/env python3
"""Assemble a relocatable arm64 runtime from pinned, checksum-verified archives."""
import concurrent.futures
import datetime
import fcntl
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CACHE = ROOT / ".local" / "desktop"
ARCHIVES = {
    "postgres": "postgres.dmg",
    "wiki": "wiki.tar.gz",
    "java": "java.tar.gz",
    "node": "node.tar.gz",
    "python": "python.tar.gz",
}


def run(*args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def checksum(path):
    with path.open("rb") as source:
        sha = hashlib.sha256()
        while data := source.read(1024 * 1024):
            sha.update(data)
    return sha.hexdigest()


def download(item):
    name, asset = item
    path = CACHE / "downloads" / ARCHIVES[name]
    with path.with_suffix(path.suffix + ".lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if path.is_file() and checksum(path) == asset["sha256"]:
            return name, path

        partial = path.with_suffix(path.suffix + ".part")
        print(f"Downloading {name} {asset['version']}", flush=True)
        for _ in range(4):
            result = subprocess.run([
                "/usr/bin/curl", "--fail", "--silent", "--show-error", "--location",
                "--http1.1", "--connect-timeout", "15", "--max-time", "180",
                "--continue-at", "-", "--output", partial, asset["url"],
            ])
            if result.returncode == 0:
                if checksum(partial) != asset["sha256"]:
                    partial.unlink()
                    raise RuntimeError(f"Checksum mismatch for {name}")
                partial.replace(path)
                return name, path
            if result.returncode == 33:
                # A server that ignores byte ranges needs a fresh request.
                partial.unlink(missing_ok=True)
        raise RuntimeError(f"Download interrupted for {name}; rerun to resume the partial file.")


def extract(archive, destination, strip=0):
    destination.mkdir()
    run("/usr/bin/tar", "-xf", archive, "-C", destination, "--strip-components", str(strip))


def copy_postgres(dmg, mount, destination):
    mount.mkdir()
    run("/usr/bin/hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount,
        dmg, stdout=subprocess.DEVNULL)
    try:
        pg = mount / "Postgres.app" / "Contents" / "Versions" / "16"
        shutil.copytree(pg, destination, symlinks=True)
        # Decision and Wiki use PostgreSQL's native SQL features. These optional
        # PL/Python extensions require a separate system Python framework.
        for path in (destination / 'lib' / 'postgresql').glob('*plpython3.dylib'):
            path.unlink()
        for path in (destination / 'share' / 'postgresql' / 'extension').glob('*plpython3*'):
            path.unlink()
    finally:
        run("/usr/bin/hdiutil", "detach", mount, stdout=subprocess.DEVNULL)


def main():
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("Build the desktop runtime on an Apple Silicon Mac.")
    (CACHE / "downloads").mkdir(parents=True, exist_ok=True)
    lockfile = HERE / "runtime-lock.json"
    output = CACHE / "runtime"
    with concurrent.futures.ThreadPoolExecutor() as pool:
        archives = dict(pool.map(download, json.loads(lockfile.read_text()).items()))

    with tempfile.TemporaryDirectory(dir=CACHE, prefix="stage-") as directory:
        stage = Path(directory)
        runtime = stage / "runtime"
        runtime.mkdir()
        copy_postgres(archives["postgres"], stage / "postgres-mount", runtime / "postgres")
        extract(archives["java"], stage / "java", strip=1)
        shutil.move(stage / "java" / "Contents" / "Home", runtime / "java")
        extract(archives["node"], stage / "node", strip=1)
        (runtime / "node" / "bin").mkdir(parents=True)
        shutil.copy2(stage / "node" / "bin" / "node", runtime / "node" / "bin")
        shutil.copy2(stage / "node" / "LICENSE", runtime / "node")
        extract(archives["wiki"], runtime / "wiki")
        # Upstream ships optional Linux accelerators. ssh2 has a JS fallback,
        # and the configured database driver is PostgreSQL.
        for path in list((runtime / 'wiki').rglob('Release')):
            if path.is_dir() and path.parent.name == 'build':
                shutil.rmtree(path)
        shutil.copy2(HERE / "wiki-entry.cjs", runtime)
        extract(archives["python"], stage / "python", strip=1)
        python = stage / "python" / "bin" / "python3"
        run(python, "-m", "venv", stage / "build-python")
        build_python = stage / "build-python" / "bin" / "python"
        run(build_python, "-m", "pip", "install", "--require-virtualenv",
            "--disable-pip-version-check", "-r", HERE / "build-requirements.txt")
        run(build_python, "-m", "PyInstaller", "--onefile", "--name", "decision-sync",
            "--distpath", runtime, "--workpath", stage / "build", "--specpath", stage,
            "--paths", ROOT / "scripts", HERE / "sync-entry.py")
        notices = runtime / "licenses"
        shutil.copytree(HERE / "licenses", notices)
        shutil.copy2(stage / "python" / "lib" / "python3.12" / "LICENSE.txt",
                     notices / "CPython.txt")
        shutil.copy2(runtime / "wiki" / "LICENSE", notices / "Wiki.js.txt")
        shutil.copy2(runtime / "node" / "LICENSE", notices / "Node.js.txt")
        pyinstaller_license = next((stage / 'build-python' / 'lib').glob(
            'python*/site-packages/pyinstaller-*.dist-info/licenses/COPYING.txt'))
        shutil.copy2(pyinstaller_license, notices / 'PyInstaller.txt')
        shutil.copy2(ROOT / "backend" / "target" / "decision-backend-0.1.0.jar",
                     runtime / "decision-backend.jar")
        run("/bin/bash", HERE / "build-java-adapter.sh", runtime / "decision-desktop.jar")
        shutil.copy2(lockfile, runtime)
        shutil.copytree(ROOT / "web" / "dist", runtime / "web")
        if output.exists():
            previous = CACHE / ("runtime-previous-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
            output.rename(previous)
        runtime.rename(output)
    print(output)


if __name__ == "__main__":
    main()
