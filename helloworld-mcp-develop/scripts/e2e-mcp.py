#!/usr/bin/env python3
"""Functional end-to-end test: MCP server (Streamable HTTP) -> hello-world-service.

Calls a running MCP server the same way an MCP client (Joule Desktop) does and
verifies that tool calls reach the real backend.

Required env:
  MCP_URL                 Base URL of the MCP server, e.g. https://hello-world-mcp-dev...
  E2E_IAS_CLIENT_ID       IAS client used as the e2e caller (client_credentials)
  E2E_IAS_CLIENT_SECRET
Optional env:
  IAS_HOST                default a3enmtmdf.accounts400.ondemand.com
  BACKEND_URL             backend base URL; its /actuator/info is printed in the summary
  E2E_UPDATE_CONFIG       "true" (default) runs updateConfiguration change + restore
  GITHUB_STEP_SUMMARY     set by GitHub Actions; a results table is appended

Scenarios:
  1. POST /mcp without token -> 401 + WWW-Authenticate resource_metadata
  2. /.well-known/oauth-protected-resource lists authorization_servers
  3. initialize + tools/list -> the 4 expected tools
  4. tools/call getGreetingConfig, createGreeting, getActuatorInfo,
     updateConfiguration (change, then always restore baseline)
  5. tools/call createGreeting with an invalid message -> isError (backend 400)
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

MCP_URL = os.environ["MCP_URL"].rstrip("/")
IAS_HOST = os.environ.get("IAS_HOST", "a3enmtmdf.accounts400.ondemand.com")
CLIENT_ID = os.environ.get("E2E_IAS_CLIENT_ID", "")
CLIENT_SECRET = os.environ.get("E2E_IAS_CLIENT_SECRET", "")
BACKEND_URL = os.environ.get("BACKEND_URL", "").rstrip("/")
UPDATE_CONFIG = os.environ.get("E2E_UPDATE_CONFIG", "true").lower() == "true"

EXPECTED_TOOLS = {"createGreeting", "updateConfiguration", "getGreetingConfig", "getActuatorInfo"}
BASELINE_CONFIG = {
    "rateLimit": {"requestsPerMinute": 10, "enabled": True},
    "validation": {"maxWords": 10, "maxCharacters": 50},
}

results = []


def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}{' - ' + detail if detail else ''}", flush=True)


def http(method, url, body=None, headers=None):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, dict(resp.headers), resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def get_token():
    if not CLIENT_ID or not CLIENT_SECRET:
        sys.exit("[FAIL] E2E_IAS_CLIENT_ID / E2E_IAS_CLIENT_SECRET are not set")
    basic = base64.b64encode(f"{CLIENT_ID}:{CLIENT_SECRET}".encode()).decode()
    status, _, body = http(
        "POST", f"https://{IAS_HOST}/oauth2/token",
        urllib.parse.urlencode({"grant_type": "client_credentials"}).encode(),
        {"Authorization": f"Basic {basic}", "Content-Type": "application/x-www-form-urlencoded"},
    )
    token = json.loads(body).get("access_token") if status == 200 else None
    if not token:
        sys.exit(f"[FAIL] IAS token request failed: HTTP {status} {body[:300]}")
    return token


def parse_rpc(headers, body):
    """Streamable HTTP may answer with plain JSON or an SSE stream."""
    ctype = next((v for k, v in headers.items() if k.lower() == "content-type"), "")
    if "text/event-stream" in ctype:
        for line in body.splitlines():
            if line.startswith("data:"):
                payload = line[5:].strip()
                if payload:
                    msg = json.loads(payload)
                    if "result" in msg or "error" in msg:
                        return msg
        raise RuntimeError(f"no JSON-RPC response in SSE body: {body[:300]}")
    return json.loads(body)


class McpSession:
    def __init__(self, token):
        self.token = token
        self.session_id = None
        self.next_id = 1

    def _headers(self):
        h = {
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        if self.session_id:
            h["Mcp-Session-Id"] = self.session_id
        return h

    def notify(self, method, params=None):
        http("POST", f"{MCP_URL}/mcp", {"jsonrpc": "2.0", "method": method, "params": params or {}}, self._headers())

    def call(self, method, params=None):
        msg = {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params or {}}
        self.next_id += 1
        status, headers, body = http("POST", f"{MCP_URL}/mcp", msg, self._headers())
        if status != 200:
            raise RuntimeError(f"{method}: HTTP {status} {body[:300]}")
        sid = next((v for k, v in headers.items() if k.lower() == "mcp-session-id"), None)
        if sid:
            self.session_id = sid
        resp = parse_rpc(headers, body)
        if "error" in resp:
            raise RuntimeError(f"{method}: JSON-RPC error {resp['error']}")
        return resp["result"]


def tool_text(result):
    return "".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text")


def wrap_args(tool_schemas, tool, payload):
    """Spring AI exposes a single record parameter as a named property (e.g. 'request')."""
    props = tool_schemas.get(tool, {}).get("properties", {})
    if len(props) == 1:
        (name,) = props.keys()
        if name not in payload:
            return {name: payload}
    return payload


def main():
    # 1. Unauthenticated request is rejected with OAuth discovery hint
    status, headers, _ = http("POST", f"{MCP_URL}/mcp", {"jsonrpc": "2.0", "id": 0, "method": "initialize"},
                              {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    www = next((v for k, v in headers.items() if k.lower() == "www-authenticate"), "")
    record("1. /mcp without token -> 401 + resource_metadata", status == 401 and "resource_metadata" in www,
           f"HTTP {status}, WWW-Authenticate={www!r}")

    # 2. Protected resource metadata
    status, _, body = http("GET", f"{MCP_URL}/.well-known/oauth-protected-resource")
    try:
        servers = json.loads(body).get("authorization_servers", []) if status == 200 else []
    except ValueError:
        servers = []
    record("2. oauth-protected-resource lists authorization_servers", bool(servers), f"HTTP {status}, {servers}")

    session = McpSession(get_token())

    # 3. initialize + tools/list
    init = session.call("initialize", {
        "protocolVersion": "2025-06-18",
        "capabilities": {},
        "clientInfo": {"name": "github-actions-e2e", "version": "1.0"},
    })
    session.notify("notifications/initialized")
    tools = session.call("tools/list")["tools"]
    names = {t["name"] for t in tools}
    schemas = {t["name"]: t.get("inputSchema", {}) for t in tools}
    record("3. initialize + tools/list", EXPECTED_TOOLS <= names,
           f"server={init.get('serverInfo', {}).get('name')}, tools={sorted(names)}")

    # 4. Tool calls reach the real backend
    res = session.call("tools/call", {"name": "getGreetingConfig", "arguments": {}})
    record("4a. getGreetingConfig", not res.get("isError"), tool_text(res)[:200])

    res = session.call("tools/call", {"name": "createGreeting",
                                      "arguments": wrap_args(schemas, "createGreeting", {"message": "Hello from CI e2e"})})
    text = tool_text(res)
    record("4b. createGreeting", not res.get("isError") and "Hello from CI e2e" in text, text[:200])

    res = session.call("tools/call", {"name": "getActuatorInfo", "arguments": {}})
    record("4c. getActuatorInfo (backend version)", not res.get("isError"), tool_text(res)[:200])

    if UPDATE_CONFIG:
        changed = json.loads(json.dumps(BASELINE_CONFIG))
        changed["validation"]["maxWords"] = 12
        try:
            res = session.call("tools/call", {"name": "updateConfiguration",
                                              "arguments": wrap_args(schemas, "updateConfiguration", changed)})
            text = tool_text(res)
            # Assert on the update call's own response (backend may run >1 replica).
            record("4d. updateConfiguration (change)",
                   not res.get("isError") and '"maxWords":12' in text.replace(" ", ""), text[:200])
        finally:
            res = session.call("tools/call", {"name": "updateConfiguration",
                                              "arguments": wrap_args(schemas, "updateConfiguration", BASELINE_CONFIG)})
            record("4e. updateConfiguration (restore baseline)", not res.get("isError"), tool_text(res)[:200])

    # 5. Backend validation error surfaces as a tool error, not a crash
    res = session.call("tools/call", {"name": "createGreeting",
                                      "arguments": wrap_args(schemas, "createGreeting", {"message": "x" * 200})})
    record("5. invalid input -> isError", bool(res.get("isError")), tool_text(res)[:200])

    backend_info = ""
    if BACKEND_URL:
        status, _, body = http("GET", f"{BACKEND_URL}/actuator/info")
        backend_info = body.strip() if status == 200 else f"HTTP {status}"

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a") as f:
            f.write("### MCP ↔ backend e2e\n\n")
            f.write(f"- MCP: `{MCP_URL}` (sha `{os.environ.get('GITHUB_SHA', 'n/a')[:12]}`)\n")
            if BACKEND_URL:
                f.write(f"- Backend: `{BACKEND_URL}` — `/actuator/info`: `{backend_info[:200]}`\n")
            f.write("\n| Scenario | Result | Detail |\n|---|---|---|\n")
            for name, ok, detail in results:
                f.write(f"| {name} | {'✅' if ok else '❌'} | {detail[:120].replace('|', '/')} |\n")

    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} scenarios passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
