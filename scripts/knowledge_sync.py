#!/usr/bin/env python3
"""Single-owner, one-way Obsidian mirror. No model calls, plugins, or login startup."""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / '.local' / 'knowledge-sync'
MARKER = 'decision-knowledge-mirror-v1'
MANIFEST = '.decision-manifest.json'
JOURNAL = '.decision-pending.json'
MAX_EXPORT_BYTES = 32 * 1024 * 1024


class SyncError(RuntimeError):
    pass


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def safe_path(path):
    """Never resolve through a symlink, including a symlink in an ancestor."""
    path = Path(os.path.abspath(os.path.expanduser(str(path))))
    for part in [*reversed(path.parents), path]:
        if part.is_symlink():
            raise SyncError('路径包含符号链接，已停止同步；请使用专用的真实目录。')
    return path


def atomic_write(path, content):
    path = safe_path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.exists() and not path.is_file():
        raise SyncError('同步目标包含同名非文件，未覆盖。')
    fd, temporary = tempfile.mkstemp(prefix='.decision-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        safe_path(path)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_json(path, default=None):
    path = safe_path(path)
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (ValueError, OSError):
        raise SyncError('本机同步状态或清单损坏，已停止；原文件保留。') from None


def identifier(value):
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError, AttributeError):
        raise SyncError('导出包含无效记录ID，未写入文件。') from None


def title(value):
    return re.sub(r'[\r\n\[\]|#<>]', ' ', str(value)).strip() or '未命名'


def body(value):
    # The mirror does not load remote images or carry executable HTML from model prose.
    parts = re.split(r'(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`)', str(value))
    for index in range(0, len(parts), 2):
        text = re.sub(r'!\[([^\]]*)\](?:\([^)]*\)|\[[^\]]*\])?', r'[图片未导出：\1]', parts[index])
        text = re.sub(r'!\[\[[^\]]*\]\]', '[附件未导出]', text)
        # Escape HTML starts while retaining generic types/comparisons as visible text.
        parts[index] = text.replace('<', '&lt;')
    return ''.join(parts).strip()


def link(path, label):
    return f'[[{path.removesuffix(".md")}|{title(label)}]]'


def entry_path(entry_id):
    return f'知识/{identifier(entry_id)}.md'


def domain_path(domain):
    return f'领域/{digest(str(domain).encode())[:24]}.md'


def reference_path(kind, record_id):
    if kind not in ('message', 'source'):
        raise SyncError('导出包含不支持的出处类型。')
    return f'出处/{kind}-{identifier(record_id)}.md'


def history_path(entry_id, revision):
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        raise SyncError('导出包含无效历史版本。')
    return f'历史/{identifier(entry_id)}-v{revision}.md'


def render(snapshot):
    """Build the entire write plan before touching the vault."""
    if snapshot.get('version') != 1 or not isinstance(snapshot.get('entries'), list):
        raise SyncError('知识导出版本不兼容，未写入文件。')
    entries = snapshot['entries']
    by_id = {identifier(item['id']): item for item in entries}
    if len(by_id) != len(entries):
        raise SyncError('知识导出存在重复ID。')
    files = {}
    domains = {}
    histories = {}
    for group in snapshot.get('histories', []):
        entry_id = identifier(group['entryId'])
        if entry_id not in by_id:
            raise SyncError('历史指向不存在的知识。')
        histories[entry_id] = group.get('versions', [])
    referenced = set()

    def note(item, historical=False):
        item_id = identifier(item['id'])
        revision = item['revision']
        history_path(item_id, revision)
        labels = item.get('domains', [])
        if not isinstance(labels, list) or any(not isinstance(d, str) for d in labels):
            raise SyncError('领域格式无效。')
        tags = ['领域/' + re.sub(r'[^\w\u4e00-\u9fff/-]+', '-', d).strip('-/') for d in labels]
        metadata = {'id': item_id, 'revision': revision, 'domains': labels,
                    'tags': tags, 'origin': 'Decision · 本人确认的讨论整理',
                    'updated': item.get('updatedAt', '')}
        text = '---\n' + '\n'.join(f'{key}: {json.dumps(value, ensure_ascii=False)}'
                                    for key, value in metadata.items()) + '\n---\n\n'
        text += f'# {title(item.get("title"))}\n\n'
        text += '> 讨论整理，经本人确认保存；不代表外部结论已被独立证实。\n'
        text += '> 本机单向镜像；修改请回到项目继续讨论并确认。附件原件未导出。\n\n'
        coverage = item.get('coverage', {})
        if coverage.get('notice'):
            text += '> 整理范围：' + body(coverage['notice']).replace('\n', ' ') + '\n\n'
        if coverage.get('truncated'):
            text += '> 本次输入有截断或有界筛选，不代表完整检查全部对话与知识。\n\n'
        text += body(item.get('markdown', '')) + '\n\n'
        if labels:
            text += '## 所属领域\n\n' + ' · '.join(link(domain_path(d), d) for d in labels) + '\n\n'
        related = [identifier(v) for v in item.get('relatedEntryIds', [])]
        if related:
            text += '## 相关知识\n\n'
            for other in related:
                if other in by_id and other != item_id:
                    text += f'- {link(entry_path(other), by_id[other]["title"])}\n'
            text += '\n'
        if item.get('refs'):
            text += '## 出处与讨论记录\n\n'
            for ref in item['refs']:
                key = (ref['kind'], identifier(ref['id']))
                referenced.add(key)
                role = {'assistant': 'AI 原话（非事实证明）', 'user': '本人原话'}.get(ref.get('role'), '正式资料')
                text += f'- {link(reference_path(*key), role)}\n'
                text += '\n'.join('  > ' + line for line in body(ref.get('quote', '')).splitlines()) + '\n\n'
        if historical:
            text += f'## 当前版本\n\n{link(entry_path(item_id), "查看当前知识")}\n'
        elif histories.get(item_id):
            text += '## 修改历史\n\n'
            for old in sorted(histories[item_id], key=lambda v: v['revision'], reverse=True):
                text += f'- {link(history_path(item_id, old["revision"]), "版本 " + str(old["revision"]))}\n'
        return text

    for item_id, item in by_id.items():
        files[entry_path(item_id)] = note(item).encode()
        for domain in item.get('domains', []):
            domains.setdefault(domain, []).append(item)
        for old in histories.get(item_id, []):
            if identifier(old['id']) != item_id:
                raise SyncError('历史ID不匹配。')
            files[history_path(item_id, old['revision'])] = note(old, True).encode()
    for domain, members in sorted(domains.items()):
        text = f'# {title(domain)}\n\nAI 根据已确认内容整理的领域；分类不代表内容已被证实。\n\n'
        text += f'{link("知识总览.md", "返回知识总览")} · {len(members)} 条知识\n\n'
        for item in sorted(members, key=lambda v: v['title']):
            text += f'- {link(entry_path(item["id"]), item["title"])}\n'
        files[domain_path(domain)] = text.encode()
    reference_items = {(ref['kind'], identifier(ref['id'])): ref for ref in snapshot.get('references', [])}
    for key in referenced:
        ref = reference_items.get(key, {})
        text = f'# {title(ref.get("title", "出处记录"))}\n\n'
        role = ref.get('role')
        text += {'assistant': 'AI 讨论原话，不是独立事实证明。',
                 'user': '本人在对话中的原话，保留原始语境。'}.get(role, '知识所引用的正式资料片段。') + '\n\n'
        excerpt = ref.get('quote') or ref.get('text')
        if not excerpt:
            # Missing/deleted originals do not prevent the version's explicit quotations being kept.
            text += '原始记录当前不可用；引用片段见知识正文，不冒充已回读原文。\n'
        else:
            text += '\n'.join('> ' + line for line in body(excerpt).splitlines()) + '\n'
        origin = ref.get('originUrl')
        if origin and urllib.parse.urlsplit(origin).scheme in ('https', 'http'):
            text += '\n原始网址：' + str(origin).replace('\n', '').replace('\r', '') + '\n'
        files[reference_path(*key)] = text.encode()
    dashboard = '# 我的知识库\n\n'
    dashboard += f'已确认知识 **{len(entries)}** 条，领域 **{len(domains)}** 个。\n\n'
    dashboard += '这里展示与 AI 讨论后由本人确认的成果。继续聊天后，可新增、合并或修订；旧版本保留。\n\n'
    dashboard += '## 按领域浏览\n\n'
    if not entries:
        dashboard += '知识库还是空的。回到项目聊清楚后，点击“整理入库”，审阅并确认第一条知识。\n'
    for domain, members in sorted(domains.items()):
        dashboard += f'- {link(domain_path(domain), domain)} · {len(members)} 条\n'
    dashboard += '\n## 最近更新\n\n'
    for item in sorted(entries, key=lambda v: v.get('updatedAt', ''), reverse=True)[:20]:
        dashboard += f'- {link(entry_path(item["id"]), item["title"])} · 版本 {item["revision"]}\n'
    dashboard += '\n## 看关系图\n\n打开 Obsidian 的“关系图谱”：领域连接知识，知识连接出处和相关知识。'
    dashboard += '可在图谱设置中按 `tag:领域/领域名` 分组着色；局部图谱更适合看一条知识的上下文。\n'
    files['知识总览.md'] = dashboard.encode()
    return files


def managed_name(value):
    if value == '知识总览.md':
        return True
    uid = r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}'
    return bool(re.fullmatch(rf'(?:知识/{uid}|历史/{uid}-v[1-9][0-9]*|'
                             rf'出处/(?:message|source)-{uid}|领域/[0-9a-f]{{24}})\.md', value))


def sync_snapshot(snapshot, vault):
    vault = safe_path(vault)
    # Per-vault lock also serializes a manual once with the background watch process.
    vault.mkdir(parents=True, exist_ok=True, mode=0o700)
    with safe_path(vault / '.decision-sync.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return apply_snapshot(snapshot, vault)


def apply_snapshot(snapshot, vault):
    vault = safe_path(vault)
    plan = render(snapshot)
    manifest_path = safe_path(vault / MANIFEST)
    previous = read_json(manifest_path, {'owner': MARKER, 'files': {}})
    if previous.get('owner') != MARKER or not isinstance(previous.get('files'), dict):
        raise SyncError('目录不属于本同步程序，未修改。')
    managed = previous['files']
    journal_path = safe_path(vault / JOURNAL)
    pending = read_json(journal_path)
    allowed = {name: {value} for name, value in managed.items()}
    if pending is not None:
        if (pending.get('owner') != MARKER or not isinstance(pending.get('previous'), dict)
                or not isinstance(pending.get('next'), dict)
                or managed not in (pending.get('base'), pending['previous'], pending['next'])):
            raise SyncError('同步恢复清单不一致，已保留所有文件。')
        # Recover an interrupted sequence only when the file still matches a known old/new hash.
        for mapping in (pending['previous'], pending['next']):
            for name, value in mapping.items():
                allowed.setdefault(name, set()).add(value)
    # Validate every old path, new path, collision and local edit before the first write/delete.
    effective = {}
    for relative in set(allowed) | set(plan):
        if not managed_name(relative):
            raise SyncError('清单包含越界路径，未修改。')
        path = safe_path(vault / relative)
        if path.exists():
            if not path.is_file():
                raise SyncError('同步路径被目录占用，未覆盖。')
            actual = digest(path.read_bytes())
            if actual not in allowed.get(relative, set()):
                raise SyncError('发现本机修改或同名文件，已暂停覆盖；请先将手写内容移到专用目录之外。')
            effective[relative] = actual
    hashes = {name: digest(content) for name, content in plan.items()}
    changed = 0
    vault.mkdir(parents=True, exist_ok=True, mode=0o700)
    needs_update = effective != hashes or managed != hashes or not manifest_path.exists()
    if needs_update:
        atomic_write(journal_path, json_bytes({'owner': MARKER, 'base': managed, 'previous': effective, 'next': hashes}))
        # Durable recovery starts from the exact pre-write state, including any recovered files.
        atomic_write(manifest_path, json_bytes({'owner': MARKER, 'files': effective, 'updatedAt': now()}))
    for name, content in plan.items():
        target = vault / name
        if hashes[name] != effective.get(name) or not target.exists():
            verify_unchanged(target, effective.get(name))
            atomic_write(target, content)
            changed += 1
    for name in set(effective) - set(plan):
        target = safe_path(vault / name)
        if target.exists():
            verify_unchanged(target, effective[name])
            target.unlink()
            changed += 1
    if needs_update:
        atomic_write(manifest_path, json_bytes({'owner': MARKER, 'files': hashes, 'updatedAt': now()}))
    if journal_path.exists():
        journal_path.unlink()
    return {'entries': len(snapshot['entries']), 'domains': len(snapshot.get('domains', [])),
            'files': len(plan), 'changedFiles': changed}


def verify_unchanged(path, expected):
    path = safe_path(path)
    actual = digest(path.read_bytes()) if path.is_file() else None
    if actual != expected or (path.exists() and not path.is_file()):
        raise SyncError('同步期间检测到本机修改，已停止覆盖；请先保留手写内容。')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SyncError('本机接口发生重定向，未向新地址发送认证。')


class Client:
    def __init__(self, base, access_code):
        parsed = urllib.parse.urlsplit(base)
        try:
            loopback = ipaddress.ip_address(parsed.hostname).is_loopback
        except (ValueError, TypeError):
            loopback = parsed.hostname == 'localhost'
        if parsed.scheme != 'http' or not loopback or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise SyncError('同步仅连接本机HTTP后端，拒绝向其他地址发送访问码。')
        self.base = base.rstrip('/')
        self.access_code = access_code
        self.token = ''
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, path, data=None):
        headers = {'Accept': 'application/json', 'Cache-Control': 'no-store'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if data is not None:
            headers.update({'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
        req = urllib.request.Request(self.base + path, headers=headers,
                                     data=json_bytes(data) if data is not None else None)
        with self.opener.open(req, timeout=20) as response:
            raw = response.read(MAX_EXPORT_BYTES + 1)
        if len(raw) > MAX_EXPORT_BYTES:
            raise SyncError('知识导出超过本机同步大小上限，已保留原文件。')
        return json.loads(raw)

    def export(self):
        if not self.token:
            self.token = self.request('/auth/login', {'accessCode': self.access_code})['accessToken']
        try:
            return self.request('/knowledge-export')
        except urllib.error.HTTPError as error:
            if error.code != 401:
                raise
            self.token = ''
            self.token = self.request('/auth/login', {'accessCode': self.access_code})['accessToken']
            return self.request('/knowledge-export')


def settings(vault=None, api_base=None):
    sys.path.insert(0, str(ROOT / 'scripts'))
    from manage import read_env
    env = dict(read_env(), **os.environ)
    vault = safe_path(vault or env.get('OBSIDIAN_VAULT_PATH') or Path.home() / 'Documents' / 'Decision Knowledge')
    base = api_base or f'http://127.0.0.1:{env.get("APP_PORT", "8080")}/api/v1'
    code = env.get('APP_OWNER_ACCESS_CODE', '')
    if not code:
        raise SyncError('缺少本机访问配置；请先初始化项目。')
    return vault, Client(base, code)


def status_update(value):
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    atomic_write(STATE / 'status.json', json_bytes(value))


def once(vault=None, api_base=None):
    vault, client = settings(vault, api_base)
    result = sync_snapshot(client.export(), vault)
    return dict(result, vault=str(vault), lastSuccessAt=now())


def watch(vault=None, api_base=None, interval=10):
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock = safe_path(STATE / 'process.lock').open('a')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SyncError('知识同步已运行，无需重复启动。') from None
    generation = secrets.token_hex(16)
    state = {'running': True, 'pid': os.getpid(), 'generation': generation,
             'status': 'starting', 'lastSuccessAt': None, 'error': None}
    status_update(state)
    failures = 0
    try:
        vault, client = settings(vault, api_base)
        state['vault'] = str(vault)
        while True:
            command = read_json(STATE / 'stop.json', {})
            if command.get('generation') == generation:
                break
            try:
                state.update(sync_snapshot(client.export(), vault))
                state.update(status='ready', lastSuccessAt=now(), error=None)
                failures = 0
            except Exception as error:
                failures += 1
                # HTTP/transport messages can contain private server responses; keep them out of logs/state.
                message = str(error) if isinstance(error, SyncError) else '本机服务暂不可用；同步将自动重试，原文件保留。'
                state.update(status='failed', error=message)
            status_update(state)
            deadline = time.monotonic() + min(60, max(interval, 2 ** min(failures, 5)))
            while time.monotonic() < deadline:
                if read_json(STATE / 'stop.json', {}).get('generation') == generation:
                    return
                time.sleep(min(1, max(0, deadline - time.monotonic())))
    finally:
        state.update(running=False, status='stopped')
        status_update(state)
        lock.close()


def process_running():
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    with safe_path(STATE / 'process.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return False
        except BlockingIOError:
            return True


def start():
    if process_running():
        print('知识库本机同步已在运行。')
        return
    log = safe_path(STATE / 'process.log')
    with log.open('ab') as stream:
        subprocess.Popen([sys.executable, str(Path(__file__).resolve()), 'watch'],
                         cwd=ROOT, stdin=subprocess.DEVNULL, stdout=stream,
                         stderr=stream, start_new_session=True)
    for _ in range(25):
        if process_running():
            print('知识库本机同步已启动（无开机自启）；状态见 knowledge-status。')
            return
        time.sleep(0.2)
    raise SyncError('知识同步未成功启动；项目服务仍可使用，请查看 knowledge-status。')


def stop():
    state = read_json(STATE / 'status.json', {})
    if process_running() and state.get('generation'):
        atomic_write(STATE / 'stop.json', json_bytes({'generation': state['generation']}))
        for _ in range(110):
            if not process_running():
                break
            time.sleep(0.2)
        else:
            raise SyncError('同步停止请求已保存，当前本机请求结束后停止。')
    print('知识库本机同步已停止；文件保留。')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['once', 'watch', 'start', 'stop', 'status'])
    parser.add_argument('--vault')
    parser.add_argument('--api-base')
    parser.add_argument('--interval', type=int, default=10)
    args = parser.parse_args()
    if args.command == 'once':
        result = once(args.vault, args.api_base)
        print(json.dumps(result, ensure_ascii=False))
    elif args.command == 'watch':
        watch(args.vault, args.api_base, max(2, args.interval))
    elif args.command == 'start':
        start()
    elif args.command == 'stop':
        stop()
    else:
        state = read_json(STATE / 'status.json', {'status': 'not_started'})
        state['running'] = process_running()
        print(json.dumps(state, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (SyncError, OSError, ValueError, urllib.error.URLError):
        # Never dump an HTTP response, access code, token, model prose, or private traceback.
        print('知识同步未完成；请检查本机服务、同步状态或文件冲突。原文件保留。', file=sys.stderr)
        sys.exit(1)
