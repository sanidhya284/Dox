#!/usr/bin/env python3
"""Post-deploy check (ADR-0011), run by deploy.sh on the server after `docker compose up -d`.

Goes through Caddy exactly like a browser, and exits non-zero on the first failure (deploy.sh then rolls back):
  1. /actuator/health is 200 {"status":"UP"} (waits up to --wait seconds for the new app to start);
  2. demo1 logs in;
  3. the "Deploy checks" group (demo1 + demo2, created on first use) is found;
  4. a WebSocket ticket is issued, the socket opens through Caddy with the site's Origin;
  5. a message is SENT over the socket and ACKED (so it was committed), and
  6. the same message is read back over REST.

Standard library only (Ubuntu 24.04 has python3). Secrets (DEMO_PASSWORD) are read from the env file, never from
the command line. With ACME_MODE=staging the certificate is untrusted by design, so TLS verification is skipped.

    python3 postdeploy_check.py --env /opt/chat/.env --tag sha-abc1234
    python3 postdeploy_check.py --env deploy/.env --base https://localhost --insecure   # local stack
"""
import argparse
import base64
import json
import os
import socket
import ssl
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOM = "Deploy checks"


def read_env(path):
    env = {}
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k] = v
    return env


class Check:
    def __init__(self, base, insecure):
        self.base = base.rstrip("/")
        self.host = self.base.split("://", 1)[1].split("/")[0]
        self.ctx = ssl.create_default_context()
        if insecure:
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE

    def http(self, method, path, body=None, token=None):
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(self.base + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=15) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            return e.code, None

    def wait_healthy(self, seconds):
        deadline = time.time() + seconds
        last = None
        while time.time() < deadline:
            try:
                last = self.http("GET", "/actuator/health")
                if last == (200, {"status": "UP"}):
                    return
            except (OSError, ssl.SSLError) as e:
                last = repr(e)
            time.sleep(3)
        fail(f"health never became UP within {seconds}s (last: {last})")

    def login(self, user, password):
        status, body = self.http("POST", "/api/auth/login", {"username": user, "password": password})
        if status != 200:
            fail(f"login as {user} returned {status}")
        return body["accessToken"]

    # ── a minimal RFC 6455 client: enough for one text frame out and frames in ──

    def ws_open(self, ticket):
        raw = socket.create_connection((self.host, 443), timeout=15)
        s = self.ctx.wrap_socket(raw, server_hostname=self.host)
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall((f"GET /ws?ticket={ticket} HTTP/1.1\r\nHost: {self.host}\r\nUpgrade: websocket\r\n"
                   f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                   f"Origin: {self.base}\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = s.recv(1024)
            if not chunk:
                fail("socket closed during the WebSocket handshake")
            head += chunk
        status_line = head.split(b"\r\n", 1)[0].decode()
        if " 101 " not in status_line + " ":
            fail(f"WebSocket handshake: {status_line}")
        return s

    @staticmethod
    def ws_send(s, text):
        data = text.encode()
        mask = os.urandom(4)
        n = len(data)
        header = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack("!H", n))
        s.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    @staticmethod
    def _recv_exact(s, n):
        buf = b""
        while len(buf) < n:
            chunk = s.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("socket closed")
            buf += chunk
        return buf

    def ws_recv_text(self, s):
        """Next text frame; pings are ignored, a close frame is a failure."""
        while True:
            b1, b2 = self._recv_exact(s, 2)
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack("!H", self._recv_exact(s, 2))[0]
            elif n == 127:
                n = struct.unpack("!Q", self._recv_exact(s, 8))[0]
            payload = self._recv_exact(s, n)
            opcode = b1 & 0x0F
            if opcode == 0x1:
                return payload.decode()
            if opcode == 0x8:
                code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else None
                fail(f"server closed the socket with {code}")


def fail(msg):
    print(f"POSTDEPLOY CHECK FAILED: {msg}", flush=True)
    sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env", default="/opt/chat/.env")
    ap.add_argument("--base")
    ap.add_argument("--tag", default="?")
    ap.add_argument("--insecure", action="store_true")
    ap.add_argument("--wait", type=int, default=180)
    a = ap.parse_args()
    env = read_env(a.env)
    base = a.base or "https://" + env["DOMAIN"]
    password = env.get("DEMO_PASSWORD", "")
    if not password:
        fail("DEMO_PASSWORD is not set in the env file; the check logs in as demo users")
    # Unverified TLS only for the staging CA, or a LOCAL stack (Caddy's own CA) that sets CHECK_INSECURE_TLS=1.
    c = Check(base, a.insecure or env.get("ACME_MODE") == "staging" or env.get("CHECK_INSECURE_TLS") == "1")

    c.wait_healthy(a.wait)
    print("ok  health UP", flush=True)
    token = c.login("demo1", password)
    print("ok  demo1 logged in", flush=True)

    status, convs = c.http("GET", "/api/conversations", token=token)
    if status != 200:
        fail(f"GET /api/conversations returned {status}")
    room = next((x for x in convs if x.get("title") == ROOM), None)
    if room is None:
        other = c.login("demo2", password)
        demo2 = json.loads(base64.urlsafe_b64decode(other.split(".")[1] + "==")).get("sub")
        status, room = c.http("POST", "/api/conversations/group", {"title": ROOM, "memberIds": [demo2]}, token)
        if status not in (200, 201):
            fail(f"creating '{ROOM}' returned {status}")
    conv = room["id"]

    status, t = c.http("POST", "/api/ws/ticket", token=token)
    if status != 200:
        fail(f"ticket returned {status}")
    s = c.ws_open(t["ticket"])
    print("ok  WebSocket open through the proxy", flush=True)

    cmid = str(uuid.uuid4())
    body = f"deploy check {a.tag} {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}"
    c.ws_send(s, json.dumps({"v": 1, "type": "send", "conversationId": conv, "clientMsgId": cmid, "body": body}))
    s.settimeout(15)
    seq = None
    while seq is None:
        frame = json.loads(c.ws_recv_text(s))
        if frame.get("type") == "ack" and frame.get("clientMsgId") == cmid:
            seq = frame["seq"]
        elif frame.get("type") == "error" and frame.get("clientMsgId") == cmid:
            fail(f"send rejected: {frame.get('code')}")
    s.close()
    print(f"ok  message sent and acked (seq {seq})", flush=True)

    status, page = c.http("GET", f"/api/conversations/{conv}/messages?after={seq - 1}&limit=1", token=token)
    if status != 200 or not page["messages"] or page["messages"][0]["body"] != body:
        fail(f"the acked message was not read back (status {status})")
    print("ok  message read back over REST", flush=True)
    print("POSTDEPLOY CHECK OK", flush=True)


if __name__ == "__main__":
    main()
