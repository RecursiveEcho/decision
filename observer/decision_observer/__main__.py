import argparse
import fcntl
import getpass
import json
import os
import time
import uuid
from pathlib import Path
from zoneinfo import ZoneInfo
from .core import ObserverError, Runner, Store, atomic_json, validate_config


def load_config(home, observation_required=True):
    path = home / 'config.json'
    if not path.exists(): raise ObserverError('Run configure first; device pairing required')
    if path.stat().st_mode & 0o077: raise ObserverError('config.json must have permissions 0600')
    c = json.loads(path.read_text())
    return validate_config(c, observation_required)


def main():
    p = argparse.ArgumentParser(description='Default-paused text sync; never starts/stops Pensieve recording')
    p.add_argument('--home', type=Path, default=Path.home()/'.decision-observer')
    sub = p.add_subparsers(dest='command', required=True)
    for cmd in ('status', 'pause', 'stop'): sub.add_parser(cmd)
    configure = sub.add_parser('configure')
    configure.add_argument('--notifications-only', action='store_true', help='Only backend and scoped device credentials; no Pensieve setup')
    notifications = sub.add_parser('notifications', help='Poll desktop deliveries; no screen-history access')
    notifications.add_argument('--once', action='store_true')
    run = sub.add_parser('run')
    run.add_argument('--problem-id', required=True)
    run.add_argument('--resume-queue', action='store_true')
    a = p.parse_args(); os.umask(0o077)
    home = a.home.expanduser(); home.mkdir(mode=0o700, parents=True, exist_ok=True)
    if a.command in ('pause', 'stop'):
        (home/'pause.request').touch(mode=0o600)
        print('已撤销本地同意，正在停止同步；底层 Pensieve 采集状态未确认，请独立停止录屏。')
        return
    if a.command == 'status':
        path = home/'status.json'
        state = json.loads(path.read_text()) if path.exists() else {'reportedState':'paused','localConsent':False}
        running = False
        with open(home/'run.lock','a') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX|fcntl.LOCK_NB)
                state.update(reportedState='paused', localConsent=False)
            except BlockingIOError: running = True
        if running and (home/'pause.request').exists(): state.update(reportedState='pausing',localConsent=False)
        state['captureState'] = 'unverified'
        state['captureNotice'] = '底层 Pensieve 采集由其独立控制，未确认停止'
        print(json.dumps(state,ensure_ascii=False,indent=2)); return
    lock_name = 'notifications.lock' if a.command == 'notifications' else 'run.lock'
    with open(home/lock_name,'a') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise ObserverError('Another command is running; pause it first')
        if a.command == 'configure':
            c = {'backendUrl': input('Backend URL including /api/v1: ').strip(),
                 'deviceId': input('Already-paired device UUID: ').strip(),
                 'deviceToken': getpass.getpass('Scoped device token (hidden): ').strip()}
            if not a.notifications_only:
                c.update({
                    'pensieveUrl': input('Pensieve URL [http://127.0.0.1:8839]: ').strip() or 'http://127.0.0.1:8839',
                    'libraryId': int(input('Selected library ID: ')),
                    'folderId': int(input('Selected folder ID: ')),
                    'pensieveTimezone': input('IANA timezone [Asia/Shanghai]: ').strip() or 'Asia/Shanghai'})
            validate_config(c, observation_required=not a.notifications_only)
            atomic_json(home/'config.json',c)
            print('配置已保存；观察与同步仍关闭。'); return
        c=load_config(home, observation_required=a.command != 'notifications'); store=Store(home)
        if a.command == 'notifications':
            runner = Runner(c,store,None)
            print('轮询桌面通知；sent 仅表示系统接口接受，Ctrl-C 退出。', flush=True)
            try:
                while True:
                    runner.deliver()
                    if a.once: break
                    time.sleep(5)
            except KeyboardInterrupt: pass
        else:
            problem=str(uuid.UUID(a.problem_id))
            print('仅同步本次启动后的文字；原图留在 Pensieve。Ctrl-C 或另一终端 pause 停止同步。',flush=True)
            Runner(c,store,problem).run(resume=a.resume_queue)


if __name__ == '__main__':
    try: main()
    except (ObserverError,ValueError,KeyError,OSError) as e:
        print('Observer: '+(str(e) if isinstance(e,ObserverError) else 'Invalid configuration or local state'))
        raise SystemExit(1)
