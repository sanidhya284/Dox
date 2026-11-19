#!/usr/bin/env python3
"""Zero-failure probe (ADR-0013, runbook): traffic through Caddy during a rolling deploy or a drill.

While it runs (until --duration seconds pass or --stop-file appears) it sends, through the public URL:
  - GETs: /actuator/health, /api/version and an authenticated GET /api/conversations;
  - authenticated POST sends (unique clientMsgId each) from demo1 and demo3 into a fresh group, under the
    production send limit (60/min per user);
  - and keeps a WebSocket client (demo2, a member of that group) connected: on any close it gets a new ticket
    and reconnects, and like the real client it fetches what it missed (catch-up after reconnect, gap fetch).
Any non-2xx answer or connection error is a FAILED request. At the end it reports requests sent / failed, sends
acked, whether every acked send is in Postgres (--psql: a command that runs one SQL query, e.g.
"docker compose -p chatp4 exec -T db psql -U chat -d chat -tA -c"), and whether the WebSocket client converged
to the conversation's rows WITHOUT a final extra fetch.

Standard library only.
    python3 zero_failure_probe.py --env deploy/.env --base https://localhost --insecure --stop-file /tmp/stop \
        --psql "docker compose -p chatp4 exec -T db psql -U chat -d chat -tA -c"
"""
import argparse
import base64
import json
import os
import shlex
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "prod"))
from postdeploy_check import read_env  # noqa: E402


class Probe:
    def __init__(self, base, insecure):
        self.base = base.rstrip("/")
        hostport = self.base.split("://", 1)[1].split("/")[0]
        self.host, _, port = hostport.partition(":")
        self.port = int(port or 443)
        self.ctx = ssl.create_default_context()
        if insecure:
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE
        self.lock = threading.Lock()
        self.sent = 0
        self.failed = []          # (t, what, detail)
        self.acked = {}           # clientMsgId -> seq
        self.t0 = time.time()

    def http(self, method, path, body=None, token=None, count=True):
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        if count:
            with self.lock:
                self.sent += 1
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=10) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw and raw[:1] in b"{[" else raw)
        except urllib.error.HTTPError as e:
            if count:
                self.fail(f"{method} {path}", f"HTTP {e.code}")
            return e.code, None
        except (OSError, ssl.SSLError) as e:
            if count:
                self.fail(f"{method} {path}", repr(e))
            return None, None

    def fail(self, what, detail):
        with self.lock:
            self.failed.append((round(time.time() - self.t0, 2), what, detail))
        print(f"FAILED +{time.time() - self.t0:.1f}s {what}: {detail}", flush=True)

    def login(self, user, password):
        status, body = self.http("POST", "/api/auth/login", {"username": user, "password": password}, count=False)
        if status != 200:
            raise SystemExit(f"setup: login as {user} returned {status}")
        return body["accessToken"]


class WsClient(threading.Thread):
    """demo2's socket: reconnects on any close, catch-up and gap fetches like the real client."""

    def __init__(self, probe, token, conv, stop):
        super().__init__(daemon=True)
        self.p, self.token, self.conv, self.stop = probe, token, conv, stop
        self.seqs = set()
        self.contiguous = 0
        self.reconnects = 0
        self.closes = []
        self.fetched = 0
        self.connected = threading.Event()

    def fetch_after(self, after):
        while True:
            status, page = self.p.http("GET", f"/api/conversations/{self.conv}/messages?after={after}&limit=100",
                                       token=self.token, count=False)
            if status != 200:
                return
            for m in page["messages"]:
                self.add(m["seq"])
                self.fetched += 1
            if not page.get("hasMore"):
                return
            after = page["messages"][-1]["seq"]

    def add(self, seq):
        self.seqs.add(seq)
        while self.contiguous + 1 in self.seqs:
            self.contiguous += 1

    def open(self):
        status, t = self.p.http("POST", "/api/ws/ticket", token=self.token, count=False)
        if status != 200:
            raise ConnectionError(f"ticket {status}")
        raw = socket.create_connection((self.p.host, self.p.port), timeout=10)
        s = self.p.ctx.wrap_socket(raw, server_hostname=self.p.host)
        key = base64.b64encode(os.urandom(16)).decode()
        origin = self.p.base
        s.sendall((f"GET /ws?ticket={t['ticket']} HTTP/1.1\r\nHost: {self.p.host}\r\nUpgrade: websocket\r\n"
                   f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                   f"Origin: {origin}\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = s.recv(1024)
            if not chunk:
                raise ConnectionError("closed during handshake")
            head += chunk
        if b" 101 " not in head.split(b"\r\n", 1)[0] + b" ":
            raise ConnectionError(head.split(b"\r\n", 1)[0].decode())
        s.settimeout(1.0)
        return s

    @staticmethod
    def send_frame(s, opcode, data):
        mask = os.urandom(4)
        n = len(data)
        header = bytes([0x80 | opcode]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack("!H", n))
        s.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    @staticmethod
    def recv_exact(s, n, stop):
        buf = b""
        while len(buf) < n:
            try:
                chunk = s.recv(n - len(buf))
            except socket.timeout:
                if stop.is_set():
                    raise ConnectionError("stopping")
                continue
            if not chunk:
                raise ConnectionError("socket closed")
            buf += chunk
        return buf

    def run(self):
        first = True
        while not self.stop.is_set():
            try:
                s = self.open()
            except (OSError, ssl.SSLError, ConnectionError) as e:
                self.closes.append((round(time.time() - self.p.t0, 1), f"connect failed: {e}"))
                time.sleep(0.3)
                continue
            if not first:
                self.reconnects += 1
            first = False
            self.fetch_after(self.contiguous)  # subscribe first, then catch up
            self.connected.set()
            try:
                while True:
                    b1, b2 = self.recv_exact(s, 2, self.stop)
                    n = b2 & 0x7F
                    if n == 126:
                        n = struct.unpack("!H", self.recv_exact(s, 2, self.stop))[0]
                    elif n == 127:
                        n = struct.unpack("!Q", self.recv_exact(s, 8, self.stop))[0]
                    payload = self.recv_exact(s, n, self.stop)
                    op = b1 & 0x0F
                    if op == 0x9:
                        self.send_frame(s, 0xA, payload)  # pong
                    elif op == 0x8:
                        code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else None
                        self.closes.append((round(time.time() - self.p.t0, 1), f"server closed {code}"))
                        break
                    elif op == 0x1:
                        f = json.loads(payload)
                        if f.get("type") == "message" and f["message"]["conversationId"] == self.conv:
                            seq = f["message"]["seq"]
                            if seq > self.contiguous + 1:
                                self.fetch_after(self.contiguous)  # gap
                            self.add(seq)
            except (OSError, ssl.SSLError, ConnectionError) as e:
                if not self.stop.is_set():
                    self.closes.append((round(time.time() - self.p.t0, 1), f"lost: {e}"))
            finally:
                try:
                    s.close()
                except OSError:
                    pass
            if not self.stop.is_set():
                time.sleep(0.2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--insecure", action="store_true")
    ap.add_argument("--duration", type=float, default=0)
    ap.add_argument("--stop-file")
    ap.add_argument("--gets-per-second", type=float, default=6)
    ap.add_argument("--sends-per-second", type=float, default=1.5)
    ap.add_argument("--psql")
    ap.add_argument("--json-out")
    a = ap.parse_args()
    env = read_env(a.env)
    p = Probe(a.base, a.insecure)
    pw = env["DEMO_PASSWORD"]
    t1, t2, t3 = p.login("demo1", pw), p.login("demo2", pw), p.login("demo3", pw)
    demo = lambda tok: json.loads(base64.urlsafe_b64decode(tok.split(".")[1] + "=="))["sub"]
    status, conv = p.http("POST", "/api/conversations/group",
                          {"title": f"probe {time.strftime('%H:%M:%S')}", "memberIds": [demo(t2), demo(t3)]},
                          t1, count=False)
    if status not in (200, 201):
        raise SystemExit(f"setup: group returned {status}")
    conv = conv["id"]
    stop = threading.Event()
    ws = WsClient(p, t2, conv, stop)
    ws.start()
    if not ws.connected.wait(15):
        raise SystemExit("setup: the WebSocket client never connected")
    print(f"probe running: conversation {conv}", flush=True)

    def getter():
        paths = [("/actuator/health", None), ("/api/version", None), ("/api/conversations", t1)]
        i = 0
        while not stop.is_set():
            path, tok = paths[i % len(paths)]
            i += 1
            p.http("GET", path, token=tok)
            time.sleep(1 / a.gets_per_second)

    def sender():
        i = 0
        while not stop.is_set():
            tok = (t1, t3)[i % 2]
            i += 1
            cmid = str(uuid.uuid4())
            status, body = p.http("POST", f"/api/conversations/{conv}/messages",
                                  {"clientMsgId": cmid, "body": f"probe {i}"}, tok)
            if status in (200, 201):
                with p.lock:
                    p.acked[cmid] = body["seq"]
            time.sleep(1 / a.sends_per_second)

    workers = [threading.Thread(target=getter, daemon=True), threading.Thread(target=sender, daemon=True)]
    for w in workers:
        w.start()
    while True:
        time.sleep(0.5)
        if a.duration and time.time() - p.t0 >= a.duration:
            break
        if a.stop_file and os.path.exists(a.stop_file):
            break
    stop_workers = time.time()
    stop.set()
    for w in workers:
        w.join(15)
    # Let the last pushes arrive before judging the socket client (no extra fetch: it must have converged itself).
    time.sleep(3)
    ws.join(5)

    all_seqs = set()
    after = 0
    while True:
        status, page = p.http("GET", f"/api/conversations/{conv}/messages?after={after}&limit=100", token=t2,
                              count=False)
        all_seqs |= {m["seq"] for m in page["messages"]}
        if not page.get("hasMore"):
            break
        after = page["messages"][-1]["seq"]
    missing_ws = sorted(all_seqs - ws.seqs)

    in_db = None
    if a.psql:
        out = subprocess.run(shlex.split(a.psql) + [
            f"SELECT client_msg_id FROM messages WHERE conversation_id = '{conv}'"], capture_output=True, text=True)
        stored = {line.strip() for line in out.stdout.splitlines() if line.strip()}
        in_db = sum(1 for c in p.acked if c in stored)

    summary = {
        "seconds": round(stop_workers - p.t0, 1),
        "requests_sent": p.sent,
        "requests_failed": len(p.failed),
        "sends_acked": len(p.acked),
        "acked_sends_in_postgres": in_db,
        "ws_reconnects": ws.reconnects,
        "ws_closes": ws.closes,
        "ws_rows_in_conversation": len(all_seqs),
        "ws_missing_without_extra_fetch": missing_ws,
        "ws_rows_from_catchup_or_gap_fetch": ws.fetched,
        "failures": p.failed,
    }
    print("PROBE " + json.dumps(summary), flush=True)
    if a.json_out:
        with open(a.json_out, "w") as f:
            json.dump(summary, f, indent=2)
    ok = not p.failed and (in_db is None or in_db == len(p.acked)) and not missing_ws
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
