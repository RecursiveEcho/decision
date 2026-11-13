#!/usr/bin/env python3
"""Two synthetic real-model checks in a disposable database; never imports personal data.

Reads only DEEPSEEK_API_KEY, DEEPSEEK_BASE_URL and DEEPSEEK_MODEL from the explicitly
provided configuration file. Credentials are passed in the child environment, not printed.
"""
import argparse
import base64
from contextlib import ExitStack
import importlib.util
from pathlib import Path
import secrets
import struct
import tempfile
from types import SimpleNamespace
import zlib

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("product_smoke", HERE / "product-smoke.py")
product = importlib.util.module_from_spec(spec)
spec.loader.exec_module(product)
native = product.native


def configuration(path):
    allowed = {"DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL", "DEEPSEEK_MODEL"}
    values = {}
    for line in path.read_text().splitlines():
        if line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() in allowed:
            values[key.strip()] = value.strip().strip('"').strip("'")
    native.require(bool(values.get("DEEPSEEK_API_KEY")), "Selected configuration has no DeepSeek key.")
    return values


def red_png():
    def chunk(kind, data):
        return struct.pack("!I", len(data)) + kind + data + struct.pack("!I", zlib.crc32(kind + data) & 0xffffffff)
    rows = (b"\x00" + b"\xff\x00\x00" * 32) * 32
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack("!2I5B", 32, 32, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=native.ROOT / ".local/desktop/runtime")
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    values = configuration(args.config)
    directory = Path(tempfile.mkdtemp(dir=native.ROOT / ".local/desktop", prefix="product-real-"))
    print(f"Isolated real-model acceptance: {directory}", flush=True)
    with ExitStack() as stack:
        sandbox = native.Sandbox(args.runtime.resolve(strict=True), directory, stack)
        password, owner = secrets.token_hex(24), secrets.token_hex(24)
        sandbox.start_pg([("decision", "decision_app", password)])
        sandbox.start_java(password, owner, SimpleNamespace(base=""), "",
                           model_env=values | {"APP_MOCK_MODE": "false"})
        api = product.API(f"http://127.0.0.1:{sandbox.api}/api/v1")
        api.login({"accessCode": owner})
        text = b"Synthetic acceptance only. Verification code: MARBLE-37."
        request = api.request("/conversations", {
            "content": "这是合成验收，不是个人事实，不要保存记忆。请读取文本附件中的验收代号，并识别图片主色，用一句话回答。",
            "thinking": "off",
            "attachments": [
                {"name": "acceptance.txt", "dataUrl": "data:text/plain;base64," + base64.b64encode(text).decode()},
                {"name": "red.png", "dataUrl": "data:image/png;base64," + base64.b64encode(red_png()).decode()},
            ],
        }, expected=202)
        product.terminal(api, request["job"]["id"])
        messages = api.request(f"/problems/{request['problem']['id']}/messages")["items"]
        answer = messages[-1]["content"]
        native.require("MARBLE-37" in answer and ("红" in answer or "red" in answer.lower()),
                       "Real model did not recognize the synthetic text and image.")
        native.require(all(item["status"] == "ready" for item in messages[0]["attachments"]),
                       "An attachment was not parsed successfully.")
        print("PASS: real DeepSeek response read synthetic TXT code and PNG colour through the new chat path.", flush=True)
        slow = api.request("/conversations", {
            "content": "这是流式停止验收，不是个人事实，不保存记忆。请按序输出1到200，每个数字用一行描述相应的合成测试编号，不要省略。",
            "thinking": "off",
        }, expected=202)
        product.partial(api, slow["job"]["id"])
        api.request(f"/jobs/{slow['job']['id']}/cancel", {})
        job = product.terminal(api, slow["job"]["id"], "cancelled")
        native.require(bool(job.get("partialContent")), "Real cancellation lost partial text.")
        native.require(len(api.request(f"/problems/{slow['problem']['id']}/messages")["items"]) == 1,
                       "Cancelled real response was committed as a completed assistant message.")
        native.require(not api.request("/memories")["items"], "Synthetic real-model acceptance unexpectedly stored personal memories.")
        print("PASS: real incremental content observed before completion, explicit stop, no completed answer or personal memory.", flush=True)
    print("All real-model acceptance services stopped; original application data was not used.", flush=True)


if __name__ == "__main__":
    main()
