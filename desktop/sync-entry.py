"""Packaged desktop helper with explicit app-owned configuration."""
import argparse
from contextlib import redirect_stdout
import json
import os
from pathlib import Path
import sys
import urllib.error

import knowledge_sync
from wiki_setup import WikiClient, initialize_wiki


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("once", "watch"):
        sync = commands.add_parser(name)
        sync.add_argument("--vault", type=Path, required=True)
        sync.add_argument("--api-base", required=True)
        sync.add_argument("--state-dir", type=Path, required=True)
    wiki = commands.add_parser("wiki-init")
    wiki.add_argument("--base", required=True)
    args = parser.parse_args()
    if args.command == "wiki-init":
        # Keep protocol output separate from the reused helper's progress messages.
        with redirect_stdout(sys.stderr):
            token = initialize_wiki(WikiClient(args.base,
                                    identity_key=os.environ["DECISION_WIKI_CHALLENGE_KEY"]),
                                    "desktop-owner@example.test",
                                    os.environ["DECISION_WIKI_ADMIN_PASSWORD"])
        print(json.dumps({"token": token}))
        return

    code = os.environ.get("APP_OWNER_ACCESS_CODE")
    if not code:
        raise knowledge_sync.SyncError("Missing owner credentials")
    vault = knowledge_sync.safe_path(args.vault)
    client = knowledge_sync.Client(args.api_base, code,
                                   identity_key=os.environ['DECISION_DESKTOP_CHALLENGE_KEY'])
    if args.command == "watch":
        knowledge_sync.watch(vault, interval=10, client=client, state_dir=args.state_dir)
    else:
        knowledge_sync.sync_snapshot(client.export(), vault)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, RuntimeError, urllib.error.URLError):
        print("Decision helper could not complete. Check the application service status.",
              file=sys.stderr)
        sys.exit(1)
