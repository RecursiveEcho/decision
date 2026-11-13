#!/usr/bin/env python3
"""Phase 1/2 integration checks against disposable PostgreSQL and a local SSE provider.

The provider is an explicit test double. It tests real HTTP streaming and cancellation,
not real-model quality. --serve keeps only this isolated stack available for UI checks.
"""
import argparse
import base64
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import secrets
import tempfile
import threading
import time
from types import SimpleNamespace
import urllib.error
import urllib.request
import uuid

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("native_smoke", HERE / "native-smoke.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class API:
    def __init__(self, base):
        self.base, self.token = base, ""

    def request(self, path, body=None, method=None, key=None, expected=200):
        verb = method or ("POST" if body is not None else "GET")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if verb == "POST":
            headers["Idempotency-Key"] = key or str(uuid.uuid4())
        request = urllib.request.Request(self.base + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=verb)
        try:
            response = native.OPENER.open(request, timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            result = json.loads(data) if data else None
            native.require(response.status == expected,
                f"{verb} {path.split('?')[0]}: expected {expected}, got {response.status}, "
                + str((result or {}).get("error", {}).get("code", "")))
            return result

    def login(self, credentials):
        result = self.request("/auth/login", credentials)
        self.token = result["accessToken"]
        return result


def provider(stack):
    state = {"requests": 0, "disconnected": 0, "mode": "ok"}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_GET(self):
            body = json.dumps({"data": [{"id": "synthetic-stream-model"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            state["requests"] += 1
            if state["mode"] == "fail":
                self.send_response(503)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            prompt = json.dumps(payload.get("messages", []), ensure_ascii=False)
            slow = "SLOW_ACCEPTANCE" in prompt
            content = "[合成HTTP替身] 已逐步读取输入，保留来源并给出可验证的回答。"
            if slow:
                content += "这是一段用于停止测试的合成内容。" * 180
            answer = json.dumps({"content": content, "evidence": [], "uncertainties": [],
                                 "conversationTitle": "合成流式验收"}, ensure_ascii=True)
            if not payload.get("stream"):
                body = json.dumps({"choices": [{"message": {"content": answer}, "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 10, "completion_tokens": 10}}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                # Deliberately split JSON escapes and Chinese code points at awkward boundaries.
                for offset in range(0, len(answer), 13):
                    data = {"choices": [{"delta": {"content": answer[offset:offset + 13]},
                                         "finish_reason": None}]}
                    self.wfile.write(("data: " + json.dumps(data) + "\n\n").encode())
                    self.wfile.flush()
                    time.sleep(0.035 if slow else 0.018)
                self.wfile.write(b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n')
                self.wfile.write(b'data: [DONE]\n\n')
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                state["disconnected"] += 1
            finally:
                self.close_connection = True

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    stack.callback(server.server_close)
    stack.callback(server.shutdown)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, state


def terminal(api, job_id, expected="succeeded"):
    def done():
        job = api.request("/jobs/" + job_id)
        if job["status"] in ("succeeded", "failed", "cancelled"):
            native.require(job["status"] == expected,
                "Unexpected terminal status: " + job["status"] + "/" + str((job.get("error") or {}).get("code")))
            return True
        return False
    native.wait_until("job terminal", None, done, timeout=50)
    return api.request("/jobs/" + job_id)


def partial(api, job_id):
    seen = {}
    def available():
        page = api.request(f"/jobs/{job_id}/events?after=0")
        job = page["job"]
        if job.get("partialContent") and job["status"] == "running":
            seen.update(page)
            return True
        native.require(job["status"] not in ("failed", "cancelled", "succeeded"),
                       "No partial response was visible before the job ended.")
        return False
    native.wait_until("visible incremental content", None, available, timeout=30)
    return seen


def exercise(api, state):
    settings = api.request("/settings/model")
    native.require(settings["configured"], "Admin fallback model missing.")
    memory = api.request("/settings/memory")
    native.require(memory["mode"] == "local" and not memory["central"]["connected"],
                   "Central memory must remain disconnected.")
    configured = api.request("/settings/model", {"provider": "deepseek", "model": "synthetic-stream-model",
        "apiKey": "synthetic-product-key"}, method="PUT")
    native.require(configured["configured"] and "apiKey" not in json.dumps(configured),
                   "Model settings returned credential material.")
    native.require(api.request("/settings/model/test", {"provider": "deepseek",
        "model": "synthetic-stream-model"})["ok"], "Model test did not run.")
    native.require(api.request("/settings/models?provider=deepseek")["items"], "Model catalog empty.")
    print("PASS: account model configuration, actual local-HTTP probe/catalog, memory remains local.", flush=True)

    created = api.request("/conversations", {"content": "STREAM_ACCEPTANCE 合成首次消息", "thinking": "off"}, expected=202)
    problem, job = created["problem"]["id"], created["job"]["id"]
    first = partial(api, job)
    terminal(api, job)
    resumed = api.request(f"/jobs/{job}/events?after={first['nextCursor']}")
    native.require(all(item["id"] > first["nextCursor"] for item in resumed["items"]), "Cursor replay duplicated events.")
    messages = api.request(f"/problems/{problem}/messages")["items"]
    native.require(len(messages) == 2 and "逐步读取" in messages[-1]["content"], "Streaming lost decoded content.")
    api.request(f"/problems/{problem}", {"pinned": True, "group": "合成验收", "title": "SEARCH_ACCEPTANCE"}, method="PATCH")
    found = api.request("/conversations/search?q=STREAM_ACCEPTANCE")["items"]
    native.require(any(row["problem"]["id"] == problem and row["messageId"] for row in found), "Body search cannot locate message.")
    print("PASS: true SSE increments before completion, event cursor recovery, pin/group and message search.", flush=True)

    baseline_memory = api.request("/memories")["items"]
    slow = api.request("/conversations", {"content": "SLOW_ACCEPTANCE 合成停止任务", "thinking": "off"}, expected=202)
    slow_id, slow_problem = slow["job"]["id"], slow["problem"]["id"]
    partial(api, slow_id)
    api.request(f"/problems/{slow_problem}/messages", {"content": "不应该产生的新消息"}, expected=409)
    api.request(f"/jobs/{slow_id}/cancel", {})
    stopped = terminal(api, slow_id, "cancelled")
    native.require(stopped.get("partialContent"), "Cancellation discarded partial response.")
    native.wait_until("provider connection closed", None, lambda: state["disconnected"] > 0, timeout=10)
    native.require(len(api.request(f"/problems/{slow_problem}/messages")["items"]) == 1, "Cancelled job wrote a late answer.")
    native.require(api.request("/memories")["items"] == baseline_memory, "Cancelled job changed memory.")
    print("PASS: same-chat collision rejected, real upstream socket closed, partial retained, no late answer/memory.", flush=True)

    state["mode"] = "fail"
    document = base64.b64encode(b"Synthetic attachment survives transport failure.").decode()
    failed = api.request("/conversations", {"content": "RETRY_ACCEPTANCE", "thinking": "off",
        "attachments": [{"name": "retained.txt", "dataUrl": "data:text/plain;base64," + document}]}, expected=202)
    terminal(api, failed["job"]["id"], "failed")
    before = state["requests"]
    time.sleep(0.7)
    native.require(before == state["requests"], "Failed job auto-replayed.")
    state["mode"] = "ok"
    key = str(uuid.uuid4())
    retry = api.request(f"/jobs/{failed['job']['id']}/retry", {}, key=key, expected=202)
    repeated = api.request(f"/jobs/{failed['job']['id']}/retry", {}, key=key, expected=202)
    native.require(retry["id"] == repeated["id"], "Idempotent retry created duplicate jobs.")
    terminal(api, retry["id"])
    retained = api.request(f"/problems/{failed['problem']['id']}/messages")["items"]
    native.require(len(retained) == 2 and retained[0]["attachments"][0]["status"] == "ready", "Retry lost attachment or duplicated messages.")
    print("PASS: upstream failure, retained parsed attachment, no automatic replay, idempotent retry.", flush=True)

    member = API(api.base)
    credentials = {"username": "product_member", "password": "synthetic-product-password"}
    registered = member.request("/auth/register", credentials, expected=201)
    member.token = registered["accessToken"]
    native.require(not member.request("/settings/model")["configured"], "Member inherited admin key.")
    native.require(not member.request("/conversations/search?q=ACCEPTANCE")["items"], "Search leaked another account.")
    member.request(f"/jobs/{slow_id}/events?after=0", expected=404)
    member.request(f"/jobs/{slow_id}/cancel", {}, expected=404)
    native.require(not member.request("/jobs?kind=chat&status=active")["items"], "Active job list leaked another account.")
    print("PASS: second-account credential/search/event/cancel/job isolation.", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=native.ROOT / ".local/desktop/runtime")
    parser.add_argument("--serve", action="store_true")
    args = parser.parse_args()
    directory = Path(tempfile.mkdtemp(dir=native.ROOT / ".local/desktop", prefix="product-smoke-"))
    print(f"Disposable product acceptance: {directory}", flush=True)
    with ExitStack() as stack:
        server, state = provider(stack)
        sandbox = native.Sandbox(args.runtime.resolve(strict=True), directory, stack)
        password, owner = secrets.token_hex(24), secrets.token_hex(24)
        sandbox.start_pg([("decision", "decision_app", password)])
        sandbox.start_java(password, owner, SimpleNamespace(base=""), "", model_env={
            "APP_MOCK_MODE": "false", "DEEPSEEK_API_KEY": "synthetic-product-key",
            "DEEPSEEK_BASE_URL": f"http://127.0.0.1:{server.server_port}",
            "DEEPSEEK_MODEL": "synthetic-stream-model",
        })
        api = API(f"http://127.0.0.1:{sandbox.api}/api/v1")
        api.login({"accessCode": owner})
        exercise(api, state)
        if args.serve:
            info = directory / "ui-session.json"
            info.write_text(json.dumps({"base": api.base.removesuffix("/api/v1"), "owner": owner,
                                       "username": "product_member", "password": "synthetic-product-password"}))
            info.chmod(0o600)
            print(f"UI_FIXTURE={info}", flush=True)
            try:
                while True:
                    time.sleep(1)
            except KeyboardInterrupt:
                pass
    print("All disposable product services stopped.", flush=True)


if __name__ == "__main__":
    main()
