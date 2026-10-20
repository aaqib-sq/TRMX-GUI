#!/usr/bin/env python3
"""Spec conformance: the Android sources vs the frozen contracts.

This is the sandbox-side gate for Phase 4+ (no JDK/SDK available here —
ADR-006). It cannot prove the Kotlin compiles; CI (GitHub Actions) does
that. What it proves is that the app cannot silently drift from:

  - docs/CONTROL-PLANE.md §3  — the 8 intent operations (id, path, argv)
  - docs/PROTOCOL.md          — every data-plane route the app calls exists
  - fixtures/v1/              — embedded test payloads are byte-identical
  - AndroidManifest.xml       — permission + package-visibility declarations
  - fixtures/v1/ payload shapes — per-route request/response contracts
    for the routes the route-presence check alone cannot prove (§6 below)
  - bridge route table        — every route the bridge serves is in the spec
  - PROTOCOL.md §10           — the error-code registry vs the fixtures
  - version story             — bridge VERSION, the §13.1 compatibility
    matrix, the `agent_tasks` flag on both sides, SHA256SUMS freshness

Run:  python3 tests/spec_conformance.py     (also runs in GitHub Actions)
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        FAILURES.append(f"{name}: {detail}")


# --- 1. CONTROL-PLANE.md §3 op table ↔ ControlOps.kt SPECS -----------------

PREFIX = "/data/data/com.termux/files/usr"
TRMX = "/data/data/com.termux/files/home/.trmx"


def doc_ops() -> dict[str, tuple[str, list[str]]]:
    md = (REPO / "docs" / "CONTROL-PLANE.md").read_text("utf-8")
    ops: dict[str, tuple[str, list[str]]] = {}
    for line in md.splitlines():
        m = re.match(r"^\| `([A-Z_]+)` \| `([^`]+)` \| `(\[.*?\])` \|", line)
        if not m:
            continue
        op, path, args = m.group(1), m.group(2), m.group(3).replace(r"\|", "|")
        path = (path.replace("$PREFIX", PREFIX).replace("$TRMX", TRMX))
        ops[op] = (path, json.loads(args))
    return ops


def kotlin_specs() -> dict[str, tuple[str, list[str]]]:
    kt = (REPO / "android" / "app" / "src" / "main" / "java" / "dev" / "trmx" / "gui"
          / "control" / "ControlOps.kt").read_text("utf-8")
    # Kotlin string templates reference the file's own compile-time constants —
    # resolve them the way the compiler does.
    kt = (kt.replace("$TERMUX_PREFIX", PREFIX)
            .replace("$TRMX_HOME", TRMX)
            .replace("$TERMUX_HOME", "/data/data/com.termux/files/home"))
    specs: dict[str, tuple[str, list[str]]] = {}
    for m in re.finditer(
            r'OpSpec\(\s*"([A-Z_]+)",\s*"([^"]+)",\s*listOf\((.*?)\),\s*"',
            kt, re.S):
        op, path, args_src = m.group(1), m.group(2), m.group(3)
        args = re.findall(r'"((?:[^"\\]|\\.)*)"', args_src)
        specs[op] = (path, [a.replace('\\"', '"') for a in args])
    return specs


print("1. control-plane op table (docs/CONTROL-PLANE.md §3 ↔ ControlOps.kt)")
doc, kt = doc_ops(), kotlin_specs()
check("both sides define the same 8 op ids",
      sorted(doc) == sorted(kt) and len(doc) == 8, f"doc={sorted(doc)} kotlin={sorted(kt)}")
for op in sorted(set(doc) & set(kt)):
    d_path, d_args = doc[op]
    k_path, k_args = kt[op]
    check(f"{op}: path matches", d_path == k_path, f"doc={d_path} kt={k_path}")
    check(f"{op}: argv matches", d_args == k_args, f"doc={d_args} kt={k_args}")

kt_src = (REPO / "android" / "app" / "src" / "main" / "java" / "dev" / "trmx" / "gui"
          / "control" / "ControlOps.kt").read_text("utf-8")
specs_block = re.search(r"val SPECS: List<OpSpec> = listOf\((.*)\)\n\n    fun spec",
                        kt_src, re.S).group(1)
check("token placeholder appears only in the PAIR op",
      specs_block.count("<TOKEN>") == 1 and "<TOKEN>" in kotlin_specs()["PAIR"][1][1],
      f"count in SPECS block={specs_block.count('<' + 'TOKEN' + '>')}")

print("2. data-plane routes (BridgeClient.kt ↔ docs/PROTOCOL.md)")
_gui = REPO / "android" / "app" / "src" / "main" / "java" / "dev" / "trmx" / "gui"
client_kt = "\n".join(
    (_gui / "net" / f if (f in ("BridgeClient.kt", "SseClient.kt")) else _gui / f)
    .read_text("utf-8")
    for f in ("BridgeClient.kt", "SseClient.kt", "AppViewModel.kt"))
protocol = (REPO / "docs" / "PROTOCOL.md").read_text("utf-8")
routes = {r.split("?")[0] for r in re.findall(r'"(/v1/[a-zA-Z0-9/?=&$_-]+)"', client_kt)}
# normalize Kotlin path templates ("$jobId") to the spec's {id} spelling
routes = {re.sub(r"\$\w+", "{id}", r) for r in routes}
check("app calls at least the handshake + jobs routes",
      {"/v1/system/info", "/v1/jobs"} <= routes, f"found={sorted(routes)}")
for r in sorted(routes):
    check(f"route {r} is defined in PROTOCOL.md", r in protocol)
check("client sends the protocol handshake header",
      '"X-TRMX-Protocol"' in client_kt and 'PROTOCOL_VERSION = "1"' in client_kt)
check("client sends bearer auth", '"Authorization", "Bearer $token"' in client_kt)
check("optional wire fields are encoded explicitly (env: null etc.)",
      "encodeDefaults = true" in client_kt)

# --- 3. embedded fixture payloads are byte-identical ------------------------

print("3. embedded fixture payloads (test sources ↔ fixtures/v1)")
fixtures = {f.name: f.read_text("utf-8").strip()
            for f in (REPO / "fixtures" / "v1").iterdir()
            if f.suffix in (".json", ".txt")}
all_blocks = 0
for tf in sorted((REPO / "android" / "app" / "src" / "test").rglob("*.kt")):
    blocks = [b.strip() for b in re.findall(r'"""(.*?)"""', tf.read_text("utf-8"), re.S)]
    for b in blocks:
        if "\n" not in b:
            continue   # single-line raw strings are synthetic test payloads, not fixtures
        all_blocks += 1
        byte_matches = [n for n, t in fixtures.items() if t == b]
        json_matches = []
        if not byte_matches:
            try:
                parsed = json.loads(b)
                json_matches = [n for n, t in fixtures.items()
                                if t.lstrip().startswith("{") and json.loads(t) == parsed]
            except ValueError:
                pass
        check(f"{tf.name}: embedded payload matches a fixture (byte or JSON)",
              len(byte_matches) + len(json_matches) == 1,
              f"byte={byte_matches} json={json_matches}")
check("embedded payload block count looks right", all_blocks >= 10, f"got {all_blocks}")

# --- 4. manifest declarations ----------------------------------------------

print("4. AndroidManifest.xml declarations")
manifest = (REPO / "android" / "app" / "src" / "main" / "AndroidManifest.xml").read_text("utf-8")
check("RUN_COMMAND permission requested",
      'android:name="com.termux.permission.RUN_COMMAND"' in manifest)
check("package visibility for com.termux declared",
      '<package android:name="com.termux" />' in manifest)

# the data plane is cleartext HTTP on loopback — targetSdk>=28 blocks that
# unless a network security config permits it (on-device round 1 lesson)
nsc_path = (REPO / "android" / "app" / "src" / "main" / "res" / "xml"
            / "network_security_config.xml")
check("network security config present", nsc_path.is_file())
if nsc_path.is_file():
    nsc = nsc_path.read_text("utf-8")
    check("cleartext permitted for loopback",
          'cleartextTrafficPermitted="true"' in nsc and "127.0.0.1" in nsc)
    check("cleartext scope is loopback-only (no base-config blanket)",
          "<base-config" not in nsc and "10.0.2.2" not in nsc)
    check("manifest references the network security config",
          'android:networkSecurityConfig="@xml/network_security_config"' in manifest)
check("no blanket cleartext fallback in manifest",
      'android:usesCleartextTraffic="true"' not in manifest)
check("INTERNET permission declared (loopback sockets need it too)",
      'android:name="android.permission.INTERNET"' in manifest)
# no committed signing key (audit §4-H2): a public keystore lets anyone
# sign a malicious "update" for the app package. Debug builds must fall
# back to AGP's per-developer local debug key; no build config may point
# at a keystore inside the repo.
ks = REPO / "android" / "config" / "debug.keystore"
check("no keystore committed under android/config", not ks.is_file(),
      "android/config/debug.keystore must not exist in the repo")
app_gradle = (REPO / "android" / "app" / "build.gradle.kts").read_text("utf-8")
check("no build config references a committed keystore",
      'config/debug.keystore' not in app_gradle,
      "build.gradle.kts must not reference config/debug.keystore")

# --- 5. Kotlin comment balance (nested comments!) ---------------------------

print("5. Kotlin block-comment balance (they NEST — see Models.kt history)")
def comment_depth_at_eof(src: str) -> int:
    """Mini lexer: Kotlin block comments nest; strings are not comments."""
    i, depth = 0, 0
    while i < len(src):
        if src.startswith("/*", i):
            depth += 1; i += 2; continue
        if src.startswith("*/", i) and depth > 0:
            depth -= 1; i += 2; continue
        if depth == 0:
            if src.startswith("//", i):
                j = src.find("\n", i)
                i = len(src) if j == -1 else j
                continue
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = len(src) if j == -1 else j + 3
                continue
            if src[i] == '"':
                j = i + 1
                while j < len(src) and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
                i = j + 1
                continue
            if src[i] == "'":
                j = i + 1
                while j < len(src) and src[j] != "'":
                    if src[j] == "\\":
                        j += 1
                    j += 1
                i = j + 1
                continue
        i += 1
    return depth

kt_files = sorted((REPO / "android" / "app" / "src").rglob("*.kt"))
check("kotlin sources present", len(kt_files) > 0)
for f in kt_files:
    depth = comment_depth_at_eof(f.read_text("utf-8"))
    check(f"{f.relative_to(REPO)} comments balanced", depth == 0,
          f"block-comment depth at EOF = {depth} (a glob pattern inside a comment?)")

# --- 6. fixture payload shapes (per-route contracts) -----------------------
#
# Audit §7-M4: the checks above prove the app *calls* documented routes
# and that Kotlin test payloads match fixtures byte-for-byte — but
# nothing validated the fixtures themselves against the per-route
# shapes PROTOCOL.md specifies, especially for routes whose payloads
# have no embedded Kotlin twin (system/policy, tools, services, agent…).
# The validators below are intentionally tolerant in exactly the way
# the wire is: unknown extra fields pass (§1.2), known fields must have
# the documented types and enum values.

print("6. fixture payload shapes (fixtures/v1 ↔ PROTOCOL.md sections)")

FIX = REPO / "fixtures" / "v1"


def fx(name: str):
    return json.loads((FIX / name).read_text("utf-8"))


def _is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _is_num(v) -> bool:
    return _is_int(v) or isinstance(v, float)


JOB_STATUSES = {"QUEUED", "STARTING", "RUNNING", "CANCELLING",
                "COMPLETED", "FAILED", "CANCELLED", "LOST"}
RISK_TIERS = {"safe", "confirm", "destructive"}
ARG_TYPES = {"string", "int", "float", "bool", "enum", "path", "url"}


def job_shape_ok(j) -> bool:
    """The §3.1 Job object. `tool` is the one historical irregularity:
    tool jobs carry it, the argv fixtures omit it — accept either, but
    type it when present."""
    if not isinstance(j, dict):
        return False
    req = {
        "job_id": lambda v: isinstance(v, str) and re.fullmatch(r"J-[0-9A-Z]+", v),
        "name": lambda v: isinstance(v, str) and 1 <= len(v) <= 200,
        "type": lambda v: v in ("argv", "shell", "tool"),
        "argv": lambda v: v is None or (isinstance(v, list)
                                        and all(isinstance(a, str) for a in v)),
        "script": lambda v: v is None or isinstance(v, str),
        "cwd": lambda v: isinstance(v, str),
        "env": lambda v: v is None or isinstance(v, dict),
        "status": lambda v: v in JOB_STATUSES,
        "created_at": lambda v: isinstance(v, str),
        "started_at": lambda v: v is None or isinstance(v, str),
        "ended_at": lambda v: v is None or isinstance(v, str),
        "pid": lambda v: v is None or _is_int(v),
        "pgid": lambda v: v is None or _is_int(v),
        "exit_code": lambda v: v is None or _is_int(v),
        "signal": lambda v: v is None or _is_int(v),
        "cancel_requested": lambda v: isinstance(v, bool),
        "cancel_reason": lambda v: v is None or isinstance(v, str),
        "error": lambda v: v is None or (isinstance(v, dict)
                                         and isinstance(v.get("code"), str)
                                         and isinstance(v.get("message"), str)),
        "timeout_s": lambda v: v is None or _is_int(v),
        "progress_pct": lambda v: v is None or _is_num(v),
        "progress_detail": lambda v: v is None or isinstance(v, str),
        "stdout_bytes": _is_int,
        "stderr_bytes": _is_int,
        "log_seq": _is_int,
        "log_truncated": lambda v: isinstance(v, bool),
        "idempotency_key": lambda v: v is None or isinstance(v, str),
    }
    if any(k not in j or not ok(j[k]) for k, ok in req.items()):
        return False
    return "tool" not in j or j["tool"] is None or isinstance(j["tool"], str)


# §2.1 system/info + §2.2 system/policy
info = fx("system.info.response.json")
check("§2.1 system.info fixture shape",
      isinstance(info.get("bridge_version"), str)
      and info.get("protocol_versions") == [1]
      and _is_int(info.get("uptime_s")) and isinstance(info.get("now"), str)
      and isinstance(info.get("load"), dict)
      and _is_int(info["load"].get("jobs_running"))
      and isinstance(info.get("memory"), dict)
      and isinstance(info.get("storage"), dict)
      and isinstance(info.get("termux"), dict)
      and isinstance(info.get("caps"), dict)
      and _is_int(info["caps"].get("max_concurrent_jobs"))
      and isinstance(info.get("features"), dict)
      and isinstance(info["features"].get("termux_api"), bool)
      and _is_int(info.get("tools_detected")),
      f"keys={sorted(info)}")
policy = fx("system.policy.response.json")
check("§2.2 system.policy fixture shape",
      isinstance(policy.get("roots"), list)
      and all(isinstance(r, str) for r in policy["roots"])
      and isinstance(policy.get("deny_write"), list)
      and _is_num(policy.get("log_ring_mb"))
      and _is_int(policy.get("max_concurrent_jobs"))
      and _is_int(policy.get("queue_depth"))
      and _is_int(policy.get("cancel_grace_ms")))

# §3 jobs
sub_argv = fx("jobs.submit.request.argv.json")
check("§3.2 argv submit fixture shape (incl. destructive-tier confirm)",
      sub_argv.get("type") == "argv"
      and isinstance(sub_argv.get("argv"), list) and len(sub_argv["argv"]) >= 1
      and all(isinstance(a, str) for a in sub_argv["argv"])
      and sub_argv.get("confirm") is True)
sub_tool = fx("jobs.submit.request.tool.json")
check("§3.2 tool submit fixture shape",
      sub_tool.get("type") == "tool"
      and isinstance(sub_tool.get("tool"), str)
      and isinstance(sub_tool.get("args"), dict))
sub_resp = fx("jobs.submit.response.json")
check("§3.2 submit response fixture shape",
      set(sub_resp) == {"job_id", "status"} and sub_resp["status"] == "QUEUED"
      and re.fullmatch(r"J-[0-9A-Z]+", sub_resp["job_id"]) is not None)
check("§3.4 job fixture shape", job_shape_ok(fx("jobs.get.response.json")))
lst = fx("jobs.list.response.json")
check("§3.3 job list fixture shape",
      isinstance(lst.get("jobs"), list) and len(lst["jobs"]) >= 1
      and all(job_shape_ok(j) for j in lst["jobs"])
      and "next_cursor" in lst
      and (lst["next_cursor"] is None or isinstance(lst["next_cursor"], str)))
cancel_req = fx("jobs.cancel.request.json")
check("§3.5 cancel request fixture shape",
      _is_int(cancel_req.get("grace_ms")) and 0 <= cancel_req["grace_ms"] <= 60000
      and isinstance(cancel_req.get("force"), bool))
check("§3.5 cancel response fixture is a Job",
      job_shape_ok(fx("jobs.cancel.response.json"))
      and fx("jobs.cancel.response.json")["status"] == "CANCELLING")

# §6 files
flist = fx("files.list.response.json")
check("§6.2 files list fixture shape",
      isinstance(flist.get("path"), str)
      and isinstance(flist.get("entries"), list) and len(flist["entries"]) >= 1
      and all(set(e) == {"name", "type", "size", "mtime", "mode", "target"}
              and e["type"] in ("file", "dir", "symlink", "other")
              and _is_int(e["size"]) and isinstance(e["name"], str)
              and (e["target"] is None or isinstance(e["target"], str))
              for e in flist["entries"]))
fops = fx("files.ops.request.json")
check("§6.3 files ops fixture shape",
      fops.get("op") in ("mkdir", "touch", "rename", "move", "copy", "delete")
      and isinstance(fops.get("path"), str))

# §7 tools
tlist = fx("tools.list.response.json")
check("§7.1 tools list fixture shape",
      isinstance(tlist.get("tools"), list) and len(tlist["tools"]) >= 1
      and all(isinstance(t.get("schema"), dict)
              and isinstance(t["schema"].get("id"), str)
              and isinstance(t["schema"].get("binary"), str)
              and t["schema"].get("risk_tier") in RISK_TIERS
              and isinstance(t.get("installed"), bool)
              and (t.get("version") is None or isinstance(t.get("version"), str))
              for t in tlist["tools"]))
schema = fx("tool.schema.yt-dlp.json")
check("§7.2 tool schema fixture shape",
      schema.get("id") == "yt-dlp" and schema.get("risk_tier") in RISK_TIERS
      and isinstance(schema.get("args"), list) and len(schema["args"]) >= 1
      and all(isinstance(a.get("name"), str) and a.get("type") in ARG_TYPES
              for a in schema["args"])
      and isinstance(schema.get("examples"), list))

# §14 services
sdef = fx("services.def.request.json")
check("§14 service definition fixture shape",
      re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", sdef.get("id", "")) is not None
      and isinstance(sdef.get("name"), str)
      and isinstance(sdef.get("tool"), str)
      and isinstance(sdef.get("args"), dict)
      and isinstance(sdef.get("autostart"), bool))
slist = fx("services.list.response.json")
check("§14 services list fixture shape",
      isinstance(slist.get("services"), list) and len(slist["services"]) >= 1
      and all(s.get("state") in ("running", "stopped")
              and isinstance(s.get("id"), str)
              and (s.get("job_id") is None or isinstance(s.get("job_id"), str))
              and (s.get("last_status") is None or s.get("last_status") in JOB_STATUSES)
              and (s.get("last_exit_code") is None or _is_int(s.get("last_exit_code")))
              for s in slist["services"]))

# §15 AI config — the response must be MASKED (no raw api_key anywhere)
ai_resp = fx("ai.config.response.json")
check("§15 ai config response fixture shape (masked)",
      ai_resp.get("exists") is True
      and ai_resp.get("mode") in ("cli", "http_api")
      and isinstance(ai_resp.get("cli", {}).get("command"), list)
      and ai_resp.get("http_api", {}).get("provider")
      in ("openai", "groq", "gemini", "openai_compatible")
      and isinstance(ai_resp["http_api"].get("api_key_set"), bool)
      and "api_key" not in ai_resp["http_api"])
ai_req = fx("ai.config.request.json")
check("§15 ai config request fixture shape",
      ai_req.get("mode") in ("cli", "http_api")
      and ai_req.get("http_api", {}).get("provider")
      in ("openai", "groq", "gemini", "openai_compatible"))

# §16 agent tasks
at_create = fx("agent.tasks.create.response.json")
check("§16 agent task create fixture shape",
      set(at_create) == {"task_id", "job_id", "status"}
      and re.fullmatch(r"AT-[0-9A-Za-z]+", at_create["task_id"]) is not None
      and at_create["status"] == "running")
at_get = fx("agent.tasks.get.response.json")
check("§16 agent task get fixture shape",
      at_get.get("status") in ("running", "awaiting_confirm", "done", "failed")
      and isinstance(at_get.get("awaiting_confirm"), bool)
      and (at_get.get("pending_action") is None
           or (isinstance(at_get["pending_action"], dict)
               and isinstance(at_get["pending_action"].get("describe"), str)
               and isinstance(at_get["pending_action"].get("tool"), str)))
      and (at_get.get("summary") is None or isinstance(at_get.get("summary"), str))
      and (at_get.get("error") is None or isinstance(at_get.get("error"), str)))

# §10 error envelope (full registry check is §7 below)
err = fx("error.response.json")
check("§10 error fixture shape",
      set(err) == {"error"}
      and isinstance(err["error"].get("code"), str)
      and isinstance(err["error"].get("message"), str)
      and "field" in err["error"] and "details" in err["error"])


def sse_frames(name: str):
    """Parse an SSE fixture into (event, id, data) frames."""
    frames = []
    for block in (FIX / name).read_text("utf-8").split("\n\n"):
        ev = eid = data = None
        for line in block.splitlines():
            if line.startswith("event: "):
                ev = line[7:]
            elif line.startswith("id: "):
                eid = int(line[4:])
            elif line.startswith("data: "):
                data = json.loads(line[6:])
        if ev is not None:
            frames.append((ev, eid, data))
    return frames


out_frames = sse_frames("jobs.output.stream.txt")
check("§4 job output stream fixture shapes",
      len(out_frames) >= 3
      and all(ev in ("stdout", "stderr", "status", "info") for ev, _, _ in out_frames)
      and all(isinstance(d, dict) and d.get("seq") == eid
              and isinstance(d.get("job_id"), str) for _, eid, d in out_frames)
      and all(d["status"] in JOB_STATUSES for ev, _, d in out_frames if ev == "status")
      and all(isinstance(d.get("text"), str) for ev, _, d in out_frames
              if ev in ("stdout", "stderr")))
ev_frames = sse_frames("events.stream.txt")
check("§5 global events fixture shapes",
      len(ev_frames) >= 2
      and all(ev in ("job.updated", "system.updated", "bridge.stopping",
                     "bridge.lifecycle", "service.updated")
              for ev, _, _ in ev_frames)
      and all(job_shape_ok(d) for ev, _, d in ev_frames if ev == "job.updated"))
agent_lines = [json.loads(ln) for ln in
               (FIX / "agent.events.stream.txt").read_text("utf-8").splitlines()
               if ln.strip()]
check("§16 agent event stream fixture shapes",
      len(agent_lines) >= 3
      and all(ln.get("t") in ("backend", "plan", "action", "result",
                               "confirm-needed", "done", "failed")
              for ln in agent_lines)
      and all(_is_int(ln["step"]) for ln in agent_lines if "step" in ln))

# --- 7. error-code registry (PROTOCOL.md §10 + additive sections) -----------

print("7. error-code registry (PROTOCOL.md §10/§14/§16 ↔ fixtures + bridge)")
_sec10 = protocol.split("## 10.")[1].split("## 11.")[0]
registry = set(re.findall(r"`([A-Z][A-Z0-9_]{2,})`", _sec10))
check("§10 registry parsed (sanity)", len(registry) >= 20, f"got {len(registry)}")
check("error fixture code is registered in §10",
      err["error"]["code"] in registry, f"registry={sorted(registry)}")
bridge_src_all = ((REPO / "termux" / "trmx-bridge.py").read_text("utf-8")
                  + (REPO / "termux" / "trmx_bridge_lib.py").read_text("utf-8"))
for code in ("SERVICE_NOT_FOUND", "SERVICE_RUNNING",
             "TASK_NOT_FOUND", "NO_PENDING_CONFIRM"):
    check(f"additive error code {code} is in PROTOCOL.md and the bridge",
          code in protocol and code in bridge_src_all)

# --- 8. bridge route table ↔ PROTOCOL.md -------------------------------------
#
# §2 above only sees routes the APP calls. The bridge serves more
# (system/policy, tools/{id}, the service actions, …) — every one of
# them must be documented, or it does not exist (PROTOCOL rule zero).

print("8. bridge route table (trmx-bridge.py ↔ PROTOCOL.md)")
bridge_routes = set()
for meth, pat in re.findall(
        r'\("(GET|POST|DELETE|PUT)",\s*re\.compile\(r"\^([^"]+)"\)',
        (REPO / "termux" / "trmx-bridge.py").read_text("utf-8")):
    norm = re.sub(r"\([^)]*\)", "{id}", pat).rstrip("$")
    bridge_routes.add((meth, norm))
check("bridge route table parsed (sanity)", len(bridge_routes) >= 25,
      f"got {len(bridge_routes)}")
# PROTOCOL spells the agent placeholder {task_id}; the bridge table and
# §2 both normalize path parameters to {id} — compare in that spelling.
protocol_norm = protocol.replace("{task_id}", "{id}")
for meth, r in sorted(bridge_routes):
    check(f"bridge route {meth} {r} is defined in PROTOCOL.md",
          r in protocol_norm)

# --- 9. version story + SHA256SUMS freshness ---------------------------------

print("9. version story (bridge 0.6.0, agent_tasks flag, §13.1 matrix, sums)")
bridge_py = (REPO / "termux" / "trmx-bridge.py").read_text("utf-8")
m_ver = re.search(r'^VERSION = "([^"]+)"', bridge_py, re.M)
check("bridge VERSION is 0.6.0 (it serves the §16 agent routes)",
      m_ver is not None and m_ver.group(1) == "0.6.0",
      f"got {m_ver.group(1) if m_ver else None}")
check("PROTOCOL.md §16 ties agent tasks to bridge ≥ 0.6.0",
      "bridge ≥ 0.6.0" in protocol)
check("PROTOCOL.md carries the §13.1 compatibility matrix",
      "Compatibility matrix" in protocol and "0.6.0" in protocol
      and "agent_tasks" in protocol)
check("bridge advertises the agent_tasks feature flag",
      '"agent_tasks": True' in bridge_py)
check("bridge advertises the service_registry feature flag",
      '"service_registry": True' in bridge_py)
models_kt = (_gui / "model" / "Models.kt").read_text("utf-8")
check("app Features model decodes agent_tasks (default false = old bridge)",
      "agent_tasks" in models_kt)
import hashlib  # noqa: E402

sums_lines = (REPO / "termux" / "SHA256SUMS").read_text("utf-8").splitlines()
sums = {}
for ln in sums_lines:
    parts = ln.split()
    if len(parts) == 2:
        sums[parts[1]] = parts[0]
check("SHA256SUMS covers exactly the 6 shipped files",
      set(sums) == {"trmx-bridge.py", "trmx_bridge_lib.py", "trmx",
                    "trmx-ai", "trmx_ai_lib.py", "trmx-agent"},
      f"got {sorted(sums)}")
for name, digest in sorted(sums.items()):
    actual = hashlib.sha256((REPO / "termux" / name).read_bytes()).hexdigest()
    check(f"SHA256SUMS fresh for {name}", actual == digest)

# --- verdict ----------------------------------------------------------------

print()
if FAILURES:
    print(f"CONFORMANCE FAILED — {len(FAILURES)} problem(s):")
    for f in FAILURES:
        print(f"  - {f}")
    sys.exit(1)
print("CONFORMANCE PASSED — app sources match the frozen contracts.")
