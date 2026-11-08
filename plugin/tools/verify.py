#!/usr/bin/env python3
"""Measure engine-bridge latency on a throwaway engine (no root, no profile edits).

Usage:
  # 1) throwaway home with the real credentials, spare port, verification overlay
  export DSH_HOME=/tmp/ebproto-home
  mkdir -p "$DSH_HOME" && cp /root/.dsh/.credentials.yaml "$DSH_HOME/"
  dsh web --patch /root/workspace/engine-bridge/tools/verify-overlay.yml \
          --host 127.0.0.1 --port 18099 --no-open &
  # 2) measure
  python3 tools/verify.py
"""

import json
import os
import time
import urllib.request

BASE = "http://127.0.0.1:18084"
ASR = "http://127.0.0.1:18082"
ROUTER = "http://127.0.0.1:18086"
INBOX = "/tmp/ebproto/inbox"
OUTBOX = "/tmp/ebproto/outbox"


def ask(text, session_id=None):
    payload = {"text": text}
    if session_id:
        payload["sessionId"] = session_id
    request = urllib.request.Request(
        f"{BASE}/ask",
        data=json.dumps(payload).encode(),
        headers={"content-type": "application/json"},
    )
    started = time.time()
    with urllib.request.urlopen(request, timeout=180) as response:
        body = json.loads(response.read())
    return time.time() - started, body


def inbox(name, text, timeout=120):
    os.makedirs(INBOX, exist_ok=True)
    os.makedirs(OUTBOX, exist_ok=True)
    with open(os.path.join(INBOX, name), "w") as handle:
        handle.write(text)
    started = time.time()
    target = os.path.join(OUTBOX, name)
    while time.time() - started < timeout:
        if os.path.exists(target) and os.path.getsize(target) > 0:
            with open(target) as handle:
                return time.time() - started, handle.read()
        time.sleep(0.05)
    return None, "TIMEOUT"


def main():
    with urllib.request.urlopen(f"{BASE}/health", timeout=5) as response:
        print("plugin health:", response.read().decode())

    for name, url in [("asr", f"{ASR}/health"), ("router", f"{ROUTER}/health")]:
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                print(f"{name:6s} health:", response.status, response.read().decode()[:80])
        except Exception as error:  # noqa: BLE001 - a failed probe is the report
            print(f"{name:6s} health: FAILED {error}")

    for label, text in [("cold", "1+1等于几？只回答数字"), ("warm", "2+2等于几？只回答数字")]:
        seconds, body = ask(text)
        print(f"{label:5s} /ask {seconds:5.2f}s reply={body.get('reply')!r} ms={body.get('ms')}")

    for name, text in [("probe1.txt", "3+3等于几？只回答数字"), ("probe2.txt", "你好")]:
        seconds, reply = inbox(name, text)
        print(f"file  {name} {('TIMEOUT' if seconds is None else f'{seconds:5.2f}s')} reply={reply[:60]!r}")


if __name__ == "__main__":
    main()
