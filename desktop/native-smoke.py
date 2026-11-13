#!/usr/bin/env python3
"""Exercise the relocated native runtime against disposable synthetic data."""
import argparse
import base64
from contextlib import ExitStack
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from knowledge_sync import Client
from wiki_setup import WikiClient, initialize_wiki

OWNER_ID = "00000000-0000-0000-0000-000000000001"
CLEAN_ENV = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "LANG": "en_US.UTF-8"}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def ports(count):
    with ExitStack() as stack:
        sockets = [stack.enter_context(socket.socket()) for _ in range(count)]
        for sock in sockets:
            sock.bind(("127.0.0.1", 0))
        return [sock.getsockname()[1] for sock in sockets]


def group_running(pid):
    try:
        os.killpg(pid, 0)
        return True
    except ProcessLookupError:
        return False


def stop(process, first_signal):
    if process.poll() is None:
        if first_signal == signal.SIGINT:
            process.send_signal(first_signal)  # PostgreSQL fast shutdown via the postmaster.
        else:
            os.killpg(process.pid, first_signal)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if process.poll() is not None and not group_running(process.pid):
            return
        time.sleep(0.1)
    if group_running(process.pid):
        os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=5)
    deadline = time.monotonic() + 5
    while group_running(process.pid) and time.monotonic() < deadline:
        time.sleep(0.1)
    require(not group_running(process.pid), "A disposable process group is still running.")
    raise RuntimeError("A disposable service group needed forced shutdown.")


class Sandbox:
    def __init__(self, runtime, directory, stack):
        self.runtime = runtime
        self.directory = directory
        self.stack = stack
        self.pg, self.api, self.wiki = ports(3)
        self.env = dict(CLEAN_ENV, HOME=str(directory), TMPDIR=str(directory / "temp"))
        (directory / "temp").mkdir(mode=0o700)

    def launch(self, name, args, additions=None, first_signal=signal.SIGTERM, cwd=None):
        log = self.stack.enter_context((self.directory / (name + ".log")).open("wb"))
        process = subprocess.Popen(
            args, env=self.env | (additions or {}), cwd=cwd or self.directory,
            stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True,
        )
        self.stack.callback(stop, process, first_signal)
        return process

    def sql(self, user, database, password, statement, *, allow_failure=False):
        result = subprocess.run(
            [self.runtime / "postgres/bin/psql", "-XAtq", "--no-password",
             "-h", "127.0.0.1", "-p", str(self.pg), "-U", user, "-d", database,
             "-v", "ON_ERROR_STOP=1"], input=statement, text=True, capture_output=True,
            env=self.env | {"PGPASSWORD": password, "PGCONNECT_TIMEOUT": "2"},
            timeout=10,
        )
        if allow_failure:
            return result.returncode
        require(result.returncode == 0, f"PostgreSQL rejected a request for {user}/{database}.")
        return result.stdout.strip()

    def start_pg(self, databases):
        admin = secrets.token_hex(24)
        data = self.directory / "pgdata"
        postgres = self.runtime / "postgres/bin"
        with (self.directory / "initdb.log").open("wb") as log:
            subprocess.run(
                [postgres / "initdb", "-D", data, "--username=decision_cluster_admin",
                 "--pwfile=/dev/stdin", "--auth=scram-sha-256", "--encoding=UTF8",
                 "--locale=C"], input=(admin + "\n").encode(), check=True, env=self.env,
                stdout=log, stderr=log, timeout=45,
            )
        process = self.launch(
            "postgres",
            [postgres / "postgres", "-D", data, "-h", "127.0.0.1", "-p", str(self.pg),
             "-c", "unix_socket_directories=", "-c", "logging_collector=off"],
            first_signal=signal.SIGINT,
        )
        wait_until("PostgreSQL authentication", process, lambda: self.sql(
            "decision_cluster_admin", "postgres", admin, "SELECT 1;",
            allow_failure=True) == 0)
        statements = ["REVOKE CONNECT, TEMPORARY ON DATABASE postgres FROM PUBLIC;",
                      "REVOKE CONNECT, TEMPORARY ON DATABASE template1 FROM PUBLIC;"]
        # Role/database names are internal constants and passwords are synthetic hex or a fixed fixture.
        for database, user, password in databases:
            statements.extend([
                f"CREATE ROLE {user} LOGIN PASSWORD '{password}';",
                f"CREATE DATABASE {database} OWNER {user};",
                f"REVOKE CONNECT, TEMPORARY ON DATABASE {database} FROM PUBLIC;",
            ])
        self.sql("decision_cluster_admin", "postgres", admin, "\n".join(statements))

    def start_wiki(self, db_password):
        data = self.directory / "wiki-data"
        data.mkdir(mode=0o700)
        config = self.directory / "wiki.yml"
        config.write_text(
            f'port: {self.wiki}\nbindIP: 127.0.0.1\noffline: true\n'
            f'dataPath: {json.dumps(str(data))}\n'
            'db:\n  type: postgres\n  host: 127.0.0.1\n'
            f'  port: {self.pg}\n  user: decision_wiki\n'
            '  pass: "$(DECISION_WIKI_DB_PASSWORD)"\n  db: decision_wiki\n'
        )
        wiki = self.runtime / "wiki"
        key = secrets.token_hex(32)
        process = self.launch("wiki", [self.runtime / "node/bin/node", self.runtime / "wiki-entry.cjs"],
                              {"CONFIG_FILE": str(config),
                               "DECISION_WIKI_DB_PASSWORD": db_password,
                               "DECISION_WIKI_CHALLENGE_KEY": key,
                               "DECISION_WIKI_PORT": str(self.wiki)}, cwd=wiki)
        client = WikiClient(f"http://127.0.0.1:{self.wiki}", identity_key=key)
        wait_until("Wiki setup", process, lambda: bool(client.home(2)))
        token = initialize_wiki(client, "desktop-fixture@example.test", secrets.token_hex(20))
        return client, token

    def start_java(self, db_password, owner_code, wiki_client, wiki_token, *, model_env=None):
        key = secrets.token_hex(32)
        self.challenge_key = key
        self.java_starts = getattr(self, "java_starts", 0) + 1
        process = self.launch(
            "java" if self.java_starts == 1 else f"java-{self.java_starts}",
            [self.runtime / "java/bin/java", "-Djava.io.tmpdir=" + self.env["TMPDIR"],
             "-Dloader.path=" + str(self.runtime / "decision-desktop.jar"),
             "-cp", self.runtime / "decision-backend.jar",
             "org.springframework.boot.loader.launch.PropertiesLauncher"],
            {
                "SERVER_ADDRESS": "127.0.0.1", "APP_PORT": str(self.api),
                "SPRING_WEB_RESOURCES_STATIC_LOCATIONS": (self.runtime / "web").as_uri() + "/",
                "DECISION_DESKTOP_CHALLENGE_KEY": key,
                "SERVER_SHUTDOWN": "graceful",
                "SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT": "3000",
                "APP_OWNER_ACCESS_CODE": owner_code, "APP_TOKEN_SECRET": secrets.token_hex(32),
                "APP_MOCK_MODE": "true",
                "DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{self.pg}/decision",
                "DATABASE_USER": "decision_app", "DATABASE_PASSWORD": db_password,
                "WIKI_BASE_URL": wiki_client.base, "WIKI_PUBLIC_URL": wiki_client.base,
                "WIKI_ACCESS_TOKEN": wiki_token, "WIKI_LOCALE": "en",
                **(model_env or {}),
            },
        )
        self.java_process = process
        client = Client(f"http://127.0.0.1:{self.api}/api/v1", owner_code)
        base = client.base.removesuffix("/api/v1")
        challenge = secrets.token_hex(32)
        expected = hmac.new(bytes.fromhex(key), challenge.encode(), hashlib.sha256).hexdigest()
        readiness = urllib.request.Request(base + "/_desktop/ready",
                                           headers={"X-Decision-Challenge": challenge})

        def ready():
            with OPENER.open(readiness, timeout=3) as response:
                return hmac.compare_digest(response.read(128).decode(), expected)

        wait_until("Java process identity and database readiness", process, ready)
        with OPENER.open(base, timeout=5) as response:
            index = response.read()
        require(b"/assets/" in index, "Vue's entry page was not served by Java.")
        for header, value in (("Host", "untrusted.local"), ("Origin", "https://untrusted.invalid"),
                              ("Sec-Fetch-Site", "cross-site"), ("Sec-Fetch-Site", "same-site")):
            try:
                OPENER.open(urllib.request.Request(base, headers={header: value}), timeout=3)
            except urllib.error.HTTPError as error:
                require(error.code == 403, f"Unexpected response to invalid {header}.")
                error.close()
            else:
                raise RuntimeError(f"Java served a request with invalid {header}.")
        try:
            client.request("/problems")
        except urllib.error.HTTPError as error:
            require(error.code == 401, "Unexpected response to unauthenticated API request.")
            error.close()
        else:
            raise RuntimeError("The desktop adapter bypassed the API filter.")
        malformed = urllib.request.Request(base + "/_desktop/ready",
                                           headers={"X-Decision-Challenge": "invalid"})
        try:
            OPENER.open(malformed, timeout=3)
        except urllib.error.HTTPError as error:
            require(error.code == 400, "Malformed readiness challenge was accepted.")
            error.close()
        else:
            raise RuntimeError("Malformed readiness challenge was accepted.")
        print("PASS: Java-served Vue, origin guard, API authentication and readiness proof.",
              flush=True)
        expected_versions = sorted(
            path.name.split("__", 1)[0][1:]
            for path in (ROOT / "backend/src/main/resources/db/migration").glob("V*__*.sql")
        )
        applied_versions = sorted(self.sql("decision_app", "decision", db_password,
            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL;").splitlines())
        require(applied_versions == expected_versions,
                "Flyway migrations do not match this checkout's schema.")
        return client


def wait_until(label, process, test, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        require(process is None or process.poll() is None, f"{label}: service exited early.")
        try:
            if test():
                return
        except (OSError, KeyError, ValueError):
            pass
        time.sleep(0.3)
    raise RuntimeError(f"{label}: readiness deadline expired.")


def seed_record(sandbox, password, kind, record, problem_id=None):
    # These records are synthetic fixtures for the real export/Wiki endpoints. No model is used.
    data = json.dumps(record, ensure_ascii=True).replace("'", "''")
    problem = f"'{problem_id}'" if problem_id else "NULL"
    sandbox.sql("decision_app", "decision", password,
                f"INSERT INTO records(id,kind,problem_id,data,account_id) VALUES "
                f"('{record['id']}','{kind}',{problem},'{data}','{OWNER_ID}');")


def check_wiki(owner, wiki, token, topic):
    job_id = owner.request(f"/topics/{topic['id']}/sync", {})["id"]
    wait_until("Java to Wiki write/readback", None,
               lambda: owner.request(f"/jobs/{job_id}")["status"] == "succeeded")
    pages = wiki.gql("{pages{list{id path}}}", token=token)["pages"]["list"]
    path = "decision/" + topic["id"]
    page = next(page for page in pages if page["path"] == path)
    readback = wiki.gql("query($id:Int!){pages{single(id:$id){content path}}}",
                        {"id": page["id"]}, token)["pages"]["single"]
    require(readback["content"] == topic["markdown"], "Wiki page content differs.")
    print("PASS: authenticated Wiki setup, Java write and independent GraphQL readback.", flush=True)


def check_accounts(owner, client):
    credentials = {"username": "desktop_fixture", "password": "synthetic-desktop-fixture"}
    client.request("/auth/register", credentials)
    client.token = client.request("/auth/login", credentials)["accessToken"]
    fixture = "Synthetic document parsed by the bundled Java subprocess."
    chat = owner.request("/conversations", {
        "content": "Synthetic native desktop smoke",
        "thinking": "off",
        "attachments": [{
            "name": "fixture.txt", "mimeType": "text/plain",
            "dataUrl": "data:text/plain;base64," + base64.b64encode(fixture.encode()).decode(),
        }],
    })
    problem = chat["problem"]["id"]
    same_origin = urllib.request.Request(owner.base + "/problems",
        headers={"Authorization": "Bearer " + owner.token,
                 "Origin": owner.base.removesuffix("/api/v1"), "Sec-Fetch-Site": "same-origin"})
    with OPENER.open(same_origin, timeout=3) as response:
        require(response.status == 200, "Authenticated same-origin requests were blocked.")
    job = chat["job"]["id"]
    wait_until("Synthetic chat with attachment", None,
               lambda: owner.request(f"/jobs/{job}")["status"] == "succeeded")
    messages = owner.request(f"/problems/{problem}/messages")["items"]
    attachment = next(item["attachments"][0] for item in messages if item.get("attachments"))
    require(attachment["status"] == "ready", "Bundled Java did not parse the attachment.")
    request = urllib.request.Request(owner.base + f"/attachments/{attachment['id']}",
                                     headers={"Authorization": "Bearer " + owner.token})
    with OPENER.open(request, timeout=10) as response:
        require(response.read() == fixture.encode(), "Attachment bytes differ.")
    require(not client.request("/problems")["items"], "Another account can see the owner list.")
    try:
        client.request(f"/attachments/{attachment['id']}")
    except urllib.error.HTTPError as error:
        require(error.code == 404, "Cross-account attachment did not return 404.")
    else:
        raise RuntimeError("Another account can read the owner attachment.")
    print("PASS: owner and member login, account isolation, native parser and attachment bytes.", flush=True)
    return problem


def check_sync(sandbox, password, owner, owner_code):
    entry = {
        "id": str(uuid.uuid4()), "title": "桌面隔离验收", "revision": 1,
        "domains": ["合成"], "markdown": "Synthetic desktop export verification.",
        "status": "active", "relatedEntryIds": [], "refs": [],
    }
    seed_record(sandbox, password, "knowledge_entry", entry)
    require(any(item["id"] == entry["id"] for item in owner.request("/knowledge-export")["entries"]),
            "Synthetic knowledge was not exported.")
    vault = sandbox.directory / "Synthetic Knowledge"
    state = sandbox.directory / "sync"
    subprocess.run(
        [sandbox.runtime / "decision-sync", "once", "--vault", vault,
         "--state-dir", state, "--api-base", owner.base],
        check=True, env=sandbox.env | {"APP_OWNER_ACCESS_CODE": owner_code,
                                     "DECISION_DESKTOP_CHALLENGE_KEY": sandbox.challenge_key},
        cwd=sandbox.directory, timeout=45,
    )
    require(any(entry["markdown"] in file.read_text() for file in vault.rglob("*.md")),
            "Standalone sync did not write the synthetic entry.")
    print("PASS: authenticated export through the standalone knowledge executable.", flush=True)


def exercise(sandbox, core_only=False):
    app_password = secrets.token_hex(24)
    wiki_password = secrets.token_hex(24)
    owner_code = secrets.token_hex(24)
    sandbox.start_pg([("decision", "decision_app", app_password),
                      ("decision_wiki", "decision_wiki", wiki_password)])
    for own, other, user, password in (
        ("decision", "decision_wiki", "decision_app", app_password),
        ("decision_wiki", "decision", "decision_wiki", wiki_password),
    ):
        require(sandbox.sql(user, own, password,
                            "SELECT rolsuper FROM pg_roles WHERE rolname=current_user;") == "f",
                "The service role has superuser access.")
        require(sandbox.sql(user, other, password, "SELECT 1;", allow_failure=True) != 0,
                "The service role can connect to the other service database.")
    print("PASS: isolated PostgreSQL cluster with two non-superuser roles.", flush=True)
    wiki, token = (WikiClient(f"http://127.0.0.1:{sandbox.wiki}"), "")
    if not core_only:
        wiki, token = sandbox.start_wiki(wiki_password)
    owner = sandbox.start_java(app_password, owner_code, wiki, token)
    owner.token = owner.request("/auth/login", {"accessCode": owner_code})["accessToken"]
    problem = check_accounts(owner, Client(owner.base, "unused"))
    topic = {"id": str(uuid.uuid4()), "problemId": problem, "title": "Synthetic desktop topic",
             "revision": 1, "markdown": "# Synthetic desktop Wiki readback"}
    seed_record(sandbox, app_password, "topic", topic, problem)
    if not core_only:
        check_wiki(owner, wiki, token, topic)
    check_sync(sandbox, app_password, owner, owner_code)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=ROOT / ".local/desktop/runtime")
    parser.add_argument("--core-only", action="store_true",
                        help="Check Java, accounts, attachments and export while omitting Wiki.")
    parser.add_argument("--backend-tests", action="store_true",
                        help="Run the existing backend suite against a new test-only cluster.")
    args = parser.parse_args()
    runtime = args.runtime.resolve(strict=True)
    cache = ROOT / ".local" / "desktop"
    cache.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(dir=cache, prefix="native-smoke-"))
    print(f"Disposable data and logs: {directory}", flush=True)
    with ExitStack() as stack:
        if args.backend_tests:
            sandbox = Sandbox(runtime, directory, stack)
            sandbox.start_pg([("decision_backend_test", "decision_test", "synthetic-test-only")])
            subprocess.run(
                ["./mvnw", "-B", "test"], cwd=ROOT / "backend", check=True,
                env=os.environ | {"TEST_DATABASE_URL":
                                  f"jdbc:postgresql://127.0.0.1:{sandbox.pg}/decision_backend_test"},
            )
        else:
            relocated = directory / "Relocated Decision Runtime"
            shutil.copytree(runtime, relocated, symlinks=True)
            exercise(Sandbox(relocated, directory, stack), args.core_only)
    print("All disposable service processes stopped.", flush=True)


if __name__ == "__main__":
    main()
