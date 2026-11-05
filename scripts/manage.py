#!/usr/bin/env python3
"""Local-only MVP lifecycle. Never prints environment secrets or removes data volumes."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / '.env'
COMPOSE = ['docker', 'compose', '--project-directory', str(ROOT), '-f', str(ROOT / 'deploy/compose.yaml')]


def read_env():
    values = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key.strip()] = value
    return values


def env():
    return dict(os.environ, **read_env())


def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, env=env(), check=True, **kwargs)


def compose(*args, **kwargs):
    return run(COMPOSE + list(args), **kwargs)


def init():
    current = read_env()
    defaults = {
        'APP_PORT': '8080', 'WEB_PORT': '8088', 'APP_PUBLIC_URL': 'http://127.0.0.1:8088',
        'DATABASE_HOST_PORT': '55432', 'DATABASE_URL': 'jdbc:postgresql://127.0.0.1:55432/decision',
        'DATABASE_USER': 'decision', 'DEEPSEEK_API_KEY': '',
        'DEEPSEEK_BASE_URL': 'https://api.deepseek.com', 'DEEPSEEK_MODEL': 'deepseek-flash',
        'WIKI_BASE_URL': 'http://127.0.0.1:8090', 'WIKI_HOST_PORT': '8090',
        'WIKI_ACCESS_TOKEN': '', 'WIKI_LOCALE': 'en', 'WIKI_PUBLIC_URL':'http://127.0.0.1:8090', 'WIKI_ADMIN_EMAIL': 'owner@decision.invalid',
        'PENSIEVE_BASE_URL': 'http://127.0.0.1:8839', 'OBSERVATION_ENABLED': 'false',
        'WECHAT_APP_ID': ''
    }
    for key in ['APP_OWNER_ACCESS_CODE', 'APP_TOKEN_SECRET', 'DATABASE_PASSWORD', 'WIKI_DATABASE_PASSWORD', 'WIKI_ADMIN_PASSWORD']:
        if not current.get(key):
            current[key] = secrets.token_urlsafe(32)
    for key, value in defaults.items():
        current.setdefault(key, value)
    oldmask = os.umask(0o077)
    try:
        ENV_FILE.write_text('# Local only. Do not commit or paste into chat.\n' + ''.join(f'{k}={v}\n' for k, v in current.items()))
        ENV_FILE.chmod(0o600)
        local = ROOT / '.local'
        local.mkdir(exist_ok=True)
        login = local / 'login.txt'
        login.write_text('本地网页: ' + current['APP_PUBLIC_URL'] + '\n访问码: ' + current['APP_OWNER_ACCESS_CODE'] + '\n')
        login.chmod(0o600)
    finally:
        os.umask(oldmask)
    print('Local configuration initialized. Existing values kept. Login details: .local/login.txt')
    print('Set DEEPSEEK_API_KEY in .env locally. No capture has been started.')


def check():
    values = read_env()
    required = ['APP_OWNER_ACCESS_CODE', 'APP_TOKEN_SECRET', 'DATABASE_PASSWORD', 'WIKI_DATABASE_PASSWORD']
    missing = [k for k in required if not values.get(k)]
    if missing:
        raise RuntimeError('Run init; missing settings: ' + ', '.join(missing))
    if len(values['APP_OWNER_ACCESS_CODE']) < 12 or len(values['APP_TOKEN_SECRET']) < 32:
        raise RuntimeError('Access code must be 12+ chars and token secret 32+ chars.')
    run(['docker', 'info', '--format', '{{.ServerVersion}}'], stdout=subprocess.DEVNULL)
    compose('--profile', 'app', 'config', '--quiet')
    print('Docker and Compose configuration: OK')
    print('DeepSeek configured:', bool(values.get('DEEPSEEK_API_KEY')))
    print('Wiki token configured:', bool(values.get('WIKI_ACCESS_TOKEN')))


def status():
    compose('--profile', 'app', 'ps')
    values = read_env()
    for name, url in [('backend', f"http://127.0.0.1:{values.get('APP_PORT','8080')}/api/v1/health"),
                      ('web', values.get('APP_PUBLIC_URL', 'http://127.0.0.1:8088')),
                      ('wiki', values.get('WIKI_BASE_URL', 'http://127.0.0.1:8090'))]:
        try:
            with urllib.request.urlopen(url, timeout=4) as response:
                print(name, response.status)
        except Exception as exc:
            print(name, 'unavailable:', type(exc).__name__)


def backup():
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    target = ROOT / '.local/backups' / stamp
    target.mkdir(parents=True, exist_ok=False, mode=0o700)
    for service, database in [('database', 'decision'), ('wiki-database', 'wiki')]:
        path = target / (database + '.dump')
        with path.open('wb') as out:
            compose('exec', '-T', service, 'pg_dump', '-U', database, '-d', database, '-Fc', stdout=out)
        path.chmod(0o600)
    with (target / 'wiki-data.tar').open('wb') as out:
        compose('exec', '-T', 'wiki', 'tar', 'cf', '-', '-C', '/wiki/data', '.', stdout=out)
    (target / 'wiki-data.tar').chmod(0o600)
    (target / 'manifest.json').write_text(json.dumps({'createdAt':stamp, 'databases':['decision','wiki'],
                                                    'includesWikiData':True, 'includesEnvironment':False}, indent=2))
    print('Backup:', target.relative_to(ROOT))
    print('Environment secrets are excluded; retain .env securely to restore the same identity.')


def verify_backup(directory):
    """Restore into fresh temporary DBs only; never overwrites the live databases."""
    target = Path(directory).resolve()
    for service, database in [('database', 'decision'), ('wiki-database', 'wiki')]:
        dump = target / (database + '.dump')
        if not dump.is_file():
            raise RuntimeError('Missing backup file: ' + str(dump))
        scratch = 'restorecheck_' + secrets.token_hex(6)
        compose('exec', '-T', service, 'createdb', '-U', database, scratch)
        try:
            with dump.open('rb') as source:
                compose('exec', '-T', service, 'pg_restore', '-U', database, '-d', scratch, '--exit-on-error', stdin=source)
            compose('exec', '-T', service, 'psql', '-U', database, '-d', scratch, '-v', 'ON_ERROR_STOP=1', '-tAc',
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
            if database == 'decision':
                present = compose('exec', '-T', service, 'psql', '-U', database, '-d', scratch, '-tAc',
                                  "SELECT to_regclass('public.records') IS NOT NULL", capture_output=True, text=True)
                if present.stdout.strip() == 't':
                    print('Restored business record counts (no personal content):', flush=True)
                    compose('exec', '-T', service, 'psql', '-U', database, '-d', scratch, '-tAc',
                            "SELECT kind,count(*) FROM records WHERE NOT deleted GROUP BY kind ORDER BY kind")
        finally:
            compose('exec', '-T', service, 'dropdb', '-U', database, scratch)
    import tarfile
    with tarfile.open(target / 'wiki-data.tar') as archive:
        print('Wiki data archive readable; entries:', len(archive.getmembers()))
    print('Backup restoration verified in isolated temporary databases. Live data unchanged.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['init','check','infra','build','up','stop','status','backup','verify-backup',
                                           'knowledge-sync','knowledge-status','knowledge-stop'])
    parser.add_argument('backup_directory', nargs='?')
    args = parser.parse_args()
    if args.command == 'init':
        init()
    elif args.command == 'check':
        check()
    elif args.command == 'infra':
        check()
        compose('up', '-d', '--wait', 'database', 'wiki-database', 'wiki')
    elif args.command == 'build':
        check()
        for relative in ['backend/Dockerfile','web/package-lock.json']:
            if not (ROOT / relative).is_file():
                raise RuntimeError('Waiting for module integration: ' + relative)
        run(['npm', '--prefix', 'web', 'ci'])
        run(['npm', '--prefix', 'web', 'run', 'build'])
        compose('--profile', 'app', 'build', 'backend')
    elif args.command == 'up':
        check()
        if not (ROOT/'web/dist/index.html').is_file():
            raise RuntimeError('Run build after integrating web/backend.')
        compose('--profile', 'app', 'up', '-d', '--wait')
        values=read_env()
        health=f"http://127.0.0.1:{values.get('APP_PORT','8080')}/api/v1/health"
        public=values.get('APP_PUBLIC_URL','http://127.0.0.1:8088').rstrip('/')
        for attempt in range(45):
            try:
                with urllib.request.urlopen(health,timeout=2) as response:
                    if json.load(response).get('status')!='ok':
                        raise ValueError('Backend not ready')
                with urllib.request.urlopen(public+'/api/v1/health',timeout=2) as response:
                    if json.load(response).get('status')!='ok':
                        raise ValueError('Proxy not ready')
                with urllib.request.urlopen(public,timeout=2) as response:
                    if response.status!=200:
                        raise ValueError('Web not ready')
                break
            except (OSError, ValueError):
                pass
            time.sleep(2)
        else:
            raise RuntimeError('Containers started but backend/web readiness failed; inspect local service logs.')
        status()
        import knowledge_sync
        knowledge_sync.start()
    elif args.command == 'stop':
        import knowledge_sync
        try:
            knowledge_sync.stop()
        except (knowledge_sync.SyncError, OSError):
            print('知识同步停止请求尚未完成；继续停止项目服务，镜像将在当前操作结束后退出。')
        finally:
            compose('--profile', 'app', 'stop')
        print('Containers stopped; data volumes retained. Observer is managed separately.')
    elif args.command == 'status':
        status()
    elif args.command == 'backup':
        backup()
    elif args.command.startswith('knowledge-'):
        import knowledge_sync
        if args.command == 'knowledge-sync':
            print(json.dumps(knowledge_sync.once(), ensure_ascii=False))
        elif args.command == 'knowledge-stop':
            knowledge_sync.stop()
        else:
            state = knowledge_sync.read_json(knowledge_sync.STATE / 'status.json', {'status': 'not_started'})
            state['running'] = knowledge_sync.process_running()
            print(json.dumps(state, ensure_ascii=False, indent=2))
    else:
        if not args.backup_directory:
            parser.error('verify-backup requires backup_directory')
        verify_backup(args.backup_directory)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        print('ERROR:', str(error), file=sys.stderr)
        sys.exit(1)
