#!/usr/bin/env python3
"""Agent-round tests — trmx-agent + agent endpoints against a real bridge.

Drives a real trmx-bridge subprocess over TCP (same harness style as
test_bridge.py) with a FAKE LLM backend: ai.json in cli mode points at a
script that pops one canned response per backend call from a JSON queue
file (deterministic, no network).

Covers: task create -> done (success path); invalid model JSON -> failed;
destructive action -> confirm-needed -> allow / deny; step cap;
trmx doctor verdicts; missing AI backend; endpoint validation (400/404/409).

Run:  python3 termux/tests/test_agent.py   (or tests/run_tests.sh agent)
stdlib only (unittest, socket, http.client) — ADR-002 rule.
"""

from __future__ import annotations

import http.client
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
TERMUX = HERE.parent
BRIDGE = TERMUX / "trmx-bridge.py"
TRMX_CLI = TERMUX / "trmx"

TOKEN = "test-token-0123456789abcdefXYZ"

FAKE_BACKEND = """#!/usr/bin/env python3
import json, os, sys
q = os.environ["TRMX_FAKE_LLM_QUEUE"]
data = json.load(open(q))
if not data["responses"]:
    sys.stderr.write("fake backend: queue empty\\n")
    sys.exit(1)
resp = data["responses"].pop(0)
json.dump(data, open(q, "w"))
sys.stdout.write(resp)
"""


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class BridgeProc:
    """A trmx-bridge subprocess bound to a test home + port."""

    def __init__(self, home: str, port: int, extra_env: dict | None = None):
        self.home, self.port = home, port
        self.extra_env = extra_env or {}
        self.proc: subprocess.Popen | None = None

    def start(self) -> None:
        logf = open(Path(self.home) / "test-agent-bridge.log", "ab")
        env = dict(os.environ)
        env.update(self.extra_env)
        self.proc = subprocess.Popen(
            [sys.executable, str(BRIDGE), "--home", self.home,
             "--port", str(self.port)],
            stdout=logf, stderr=logf, stdin=subprocess.DEVNULL, env=env)
        deadline = time.time() + 15
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", self.port),
                                              timeout=0.3):
                    return
            except OSError:
                if self.proc.poll() is not None:
                    break
                time.sleep(0.05)
        raise AssertionError("bridge did not start "
                             f"(rc={self.proc.poll() if self.proc else '?'})")

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


class AgentTestBase(unittest.TestCase):
    extra_env: dict = {}

    def setUp(self) -> None:
        self.home = tempfile.mkdtemp(prefix="trmx-agent-home-")
        self.port = free_port()
        (Path(self.home) / "bridge.json").write_text(
            json.dumps({"token": TOKEN, "port": self.port}))
        # fake LLM backend: pops one canned response per call
        self.fake = Path(self.home) / "fake-llm.py"
        self.fake.write_text(FAKE_BACKEND)
        self.fake.chmod(0o755)
        self.queue = Path(self.home) / "llm-queue.json"
        (Path(self.home) / "ai.json").write_text(json.dumps({
            "mode": "cli",
            "cli": {"command": [str(self.fake)], "timeout_s": 30}}))
        # a harmless user tool the agent may "run" in confirm tests
        tools = Path(self.home) / "tools"
        tools.mkdir(exist_ok=True)
        (tools / "test-noop.json").write_text(json.dumps({
            "id": "test-noop", "name": "Test no-op",
            "description": "exits immediately, no side effects",
            "binary": "true", "risk_tier": "safe",
            "fixed_argv": [], "args": []}))
        self.bridge = None
        env = dict(self.extra_env)
        env["TRMX_FAKE_LLM_QUEUE"] = str(self.queue)
        self.queue.write_text(json.dumps({"responses": []}))
        self.bridge = BridgeProc(self.home, self.port, env)
        self.bridge.start()

    def tearDown(self) -> None:
        if getattr(self, "bridge", None):
            self.bridge.stop()

    # -- helpers ----------------------------------------------------

    def queue_responses(self, *texts: str) -> None:
        self.queue.write_text(json.dumps({"responses": list(texts)}))

    def api(self, method: str, path: str, body: dict | None = None,
            raw: bool = False):
        headers = {"Authorization": f"Bearer {TOKEN}",
                   "X-TRMX-Protocol": "1",
                   "Content-Type": "application/json"}
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        conn.request(method, path,
                     body=json.dumps(body or {}).encode(),
                     headers=headers)
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        if raw:
            return resp.status, data
        try:
            return resp.status, json.loads(data.decode())
        except ValueError:
            return resp.status, data.decode("utf-8", "replace")

    def wait_task(self, task_id: str, want: set[str],
                  timeout: float = 60.0) -> dict:
        deadline = time.time() + timeout
        last: dict = {}
        while time.time() < deadline:
            st, last = self.api("GET", f"/v1/agent/tasks/{task_id}")
            self.assertEqual(st, 200, last)
            if last["status"] in want:
                return last
            time.sleep(0.5)
        self.fail(f"task {task_id} never reached {want}: {last}")

    def agent_events(self, job_id: str) -> list[dict]:
        st, raw = self.api(
            "GET", f"/v1/jobs/{job_id}/output?follow=0&stream=stdout", raw=True)
        self.assertEqual(st, 200)
        text = raw.decode("utf-8", "replace")
        chunks: list[str] = []
        event, buf = None, []
        for line in text.splitlines():
            if line == "":
                if event == "stdout" and buf:
                    try:
                        chunks.append(json.loads("".join(buf))["text"])
                    except (ValueError, KeyError):
                        pass
                event, buf = None, []
            elif line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                buf.append(line[5:].strip())
        events = []
        for ln in "".join(chunks).splitlines():
            ln = ln.strip()
            if ln.startswith("{"):
                try:
                    events.append(json.loads(ln))
                except ValueError:
                    pass
        return events

    def create_task(self, instruction: str) -> dict:
        st, resp = self.api("POST", "/v1/agent/tasks",
                            {"instruction": instruction})
        self.assertEqual(st, 201, resp)
        self.assertTrue(resp["task_id"].startswith("AT-"))
        self.assertIn("job_id", resp)
        return resp

    # -- tests ------------------------------------------------------

    def test_10_done_path(self):
        self.queue_responses(json.dumps(
            {"op": "done", "summary": "all good, nothing to do"}))
        t = self.create_task("say hi")
        got = self.wait_task(t["task_id"], {"done"})
        self.assertEqual(got["summary"], "all good, nothing to do")
        st, job = self.api("GET", f"/v1/jobs/{t['job_id']}")
        self.assertEqual(job["status"], "COMPLETED")
        events = self.agent_events(t["job_id"])
        kinds = [e["t"] for e in events]
        self.assertIn("backend", kinds)
        self.assertIn("done", kinds)
        backend = next(e for e in events if e["t"] == "backend")
        self.assertEqual(backend["mode"], "cli")
        # the token never appears in the event stream
        self.assertNotIn(TOKEN, json.dumps(events))

    def test_11_invalid_model_json(self):
        # One bad reply earns a corrective retry (small local models wrap
        # JSON in prose); TWO bad replies in a row still fail the task.
        self.queue_responses("this is not json at all",
                             "still not json, sorry")
        t = self.create_task("do something")
        got = self.wait_task(t["task_id"], {"failed"})
        self.assertIn("rejected", got["error"])
        events = self.agent_events(t["job_id"])
        self.assertTrue(any(e["t"] == "failed" for e in events))

    def test_11c_prose_block_then_action_picked(self):
        # audit §2-M3: a chatty model puts a prose/example object FIRST
        # and the real action LAST. The first block below parses as JSON
        # but is NOT a valid action (a "say" with no text). The old
        # first-block extract_json handed it to validate_action, which
        # rejected it and burned the one corrective retry — with only
        # ONE queued response that retry would hit an empty queue and
        # fail the task. The validator-aware extract must pick the
        # trailing valid action on the first reply.
        self.queue_responses(
            'Sure! An action looks like {"op": "say"} — here is mine: '
            '{"op": "done", "summary": "picked the real action"}')
        t = self.create_task("do something")
        got = self.wait_task(t["task_id"], {"done"})
        self.assertEqual(got["summary"], "picked the real action")

    def test_11b_invalid_then_valid_recovers(self):
        self.queue_responses(
            "let me think out loud... no json here",
            json.dumps({"op": "done", "summary": "recovered and done"}))
        t = self.create_task("do something")
        got = self.wait_task(t["task_id"], {"done"})
        self.assertEqual(got["summary"], "recovered and done")

    def test_12_confirm_allow_runs_tool(self):
        self.queue_responses(
            json.dumps({"op": "do", "tool": "submit_tool",
                        "args": {"tool": "test-noop", "args": {}},
                        "describe": "delete the old cache files"}),
            json.dumps({"op": "done", "summary": "cleaned"}))
        t = self.create_task("clean up")
        got = self.wait_task(t["task_id"], {"awaiting_confirm"})
        self.assertIn("delete", got["pending_action"]["describe"])
        st, resp = self.api("POST",
                            f"/v1/agent/tasks/{t['task_id']}/confirm",
                            {"decision": "allow"})
        self.assertEqual(st, 200, resp)
        got = self.wait_task(t["task_id"], {"done"})
        self.assertEqual(got["summary"], "cleaned")
        events = self.agent_events(t["job_id"])
        kinds = [e["t"] for e in events]
        self.assertIn("confirm-needed", kinds)
        self.assertIn("action", kinds)
        self.assertIn("result", kinds)

    def test_13_confirm_deny_stops(self):
        self.queue_responses(
            json.dumps({"op": "do", "tool": "submit_tool",
                        "args": {"tool": "git-clone",
                                 "args": {"url": "https://example.com/x.git"}},
                        "describe": "clone the repo"}))
        t = self.create_task("get the code")
        got = self.wait_task(t["task_id"], {"awaiting_confirm"})
        # git-clone is risk_tier confirm -> confirm even with innocent wording
        self.assertEqual(got["pending_action"]["tool"], "submit_tool")
        st, resp = self.api("POST",
                            f"/v1/agent/tasks/{t['task_id']}/confirm",
                            {"decision": "deny"})
        self.assertEqual(st, 200, resp)
        got = self.wait_task(t["task_id"], {"failed"})
        self.assertIn("denied", got["error"])

    def test_14_agent_cannot_submit_itself(self):
        # The forbidden action is rejected, retried once like any invalid
        # output, rejected again -- and NEVER executed either time.
        bad = json.dumps({"op": "do", "tool": "submit_tool",
                          "args": {"tool": "agent",
                                   "args": {"task_id": "AT-nope"}},
                          "describe": "spawn another agent"})
        self.queue_responses(bad, bad)
        t = self.create_task("recurse")
        got = self.wait_task(t["task_id"], {"failed"})
        self.assertIn("itself", got["error"])

    def test_15_endpoint_validation(self):
        st, resp = self.api("POST", "/v1/agent/tasks", {"instruction": ""})
        self.assertEqual(st, 400)
        self.assertEqual(resp["error"]["code"], "VALIDATION_FAILED")
        st, resp = self.api("GET", "/v1/agent/tasks/AT-doesnotexist")
        self.assertEqual(st, 404)
        self.assertEqual(resp["error"]["code"], "TASK_NOT_FOUND")
        t = self.create_task("nothing pending here")
        st, resp = self.api("POST", f"/v1/agent/tasks/{t['task_id']}/confirm",
                            {"decision": "allow"})
        self.assertEqual(st, 409)
        self.assertEqual(resp["error"]["code"], "NO_PENDING_CONFIRM")
        st, resp = self.api("POST", f"/v1/agent/tasks/{t['task_id']}/confirm",
                            {"decision": "maybe"})
        self.assertEqual(st, 400)


    def _restart_with_deadline(self, seconds: int) -> None:
        self.bridge.stop()
        self.bridge = BridgeProc(self.home, self.port, {
            **self.extra_env, "TRMX_FAKE_LLM_QUEUE": str(self.queue),
            "TRMX_AGENT_TASK_TIMEOUT_S": str(seconds)})
        self.bridge.start()

    def _submit_sleeping_child(self) -> tuple[dict, str]:
        (Path(self.home) / "tools" / "test-sleep.json").write_text(json.dumps({
            "id": "test-sleep", "name": "Test sleeper", "binary": "sleep",
            "risk_tier": "safe", "fixed_argv": ["60"], "args": []}))
        self.api("POST", "/v1/tools/refresh", {})
        self.queue_responses(json.dumps({
            "op": "do", "tool": "submit_tool", "args": {"tool": "test-sleep"},
            "describe": "Wait briefly"}))
        task = self.create_task("wait briefly")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            _, resp = self.api("GET", "/v1/jobs")
            children = [j for j in resp["jobs"] if j.get("tool") == "test-sleep"]
            if children:
                return task, children[0]["job_id"]
            time.sleep(0.05)
        self.fail("agent did not submit child job")

    def _assert_child_cancelled(self, job_id: str) -> None:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            _, child = self.api("GET", f"/v1/jobs/{job_id}")
            if child["status"] == "CANCELLED":
                return
            time.sleep(0.05)
        self.fail(f"child job was orphaned: {child}")

    def test_15b_stop_cancels_child(self):
        task, child = self._submit_sleeping_child()
        self.api("POST", f"/v1/jobs/{task['job_id']}/cancel", {})
        self._assert_child_cancelled(child)
        self.wait_task(task["task_id"], {"failed", "cancelled"})

    def test_15c_task_deadline_cancels_child(self):
        self._restart_with_deadline(3)
        task, child = self._submit_sleeping_child()
        got = self.wait_task(task["task_id"], {"failed"}, timeout=10)
        self.assertIn("task timed out", got["error"])
        self._assert_child_cancelled(child)
        self.assertTrue(any(e.get("error") == "task timed out"
                            for e in self.agent_events(task["job_id"])))

    def test_15d_task_deadline_during_confirm(self):
        self._restart_with_deadline(3)
        self.queue_responses(json.dumps({
            "op": "do", "tool": "submit_tool", "args": {"tool": "test-noop"},
            "describe": "Delete a test item"}))
        task = self.create_task("ask me before deleting")
        got = self.wait_task(task["task_id"], {"failed"}, timeout=10)
        self.assertIn("task timed out", got["error"])
        self.assertFalse((Path(self.home) / "agent" / "pending" /
                          f"{task['task_id']}.json").exists())
        self.assertTrue(any(e["t"] == "confirm-needed"
                            for e in self.agent_events(task["job_id"])))

    def test_15e_task_deadline_during_model_call(self):
        self._restart_with_deadline(3)
        self.fake.write_text("#!/usr/bin/env python3\nimport time\ntime.sleep(60)\n")
        task = self.create_task("wait for a stuck model")
        got = self.wait_task(task["task_id"], {"failed"}, timeout=10)
        self.assertIn("task timed out", got["error"])

    def test_16_no_backend_configured(self):
        (Path(self.home) / "ai.json").unlink()
        t = self.create_task("hello")
        got = self.wait_task(t["task_id"], {"failed"})
        self.assertIn("not configured", got["error"])

    def test_17_doctor_healthy(self):
        r = subprocess.run([sys.executable, str(TRMX_CLI), "doctor"],
                           capture_output=True, text=True,
                           env={**os.environ, "TRMX_HOME": self.home})
        self.assertEqual(r.returncode, 0, r.stderr)
        report = json.loads(r.stdout)
        self.assertEqual(report["verdict"], "healthy")
        self.assertTrue(report["api_ok"])
        self.assertTrue(report["token_set"])
        self.assertIsNotNone(report["bridge_version"])
        self.assertNotIn(TOKEN, r.stdout)

    def test_18_doctor_unpaired_home(self):
        empty = tempfile.mkdtemp(prefix="trmx-doctor-empty-")
        r = subprocess.run([sys.executable, str(TRMX_CLI), "doctor"],
                           capture_output=True, text=True,
                           env={**os.environ, "TRMX_HOME": empty})
        self.assertEqual(r.returncode, 3, r.stderr)
        report = json.loads(r.stdout)
        self.assertEqual(report["verdict"], "unpaired")
        self.assertFalse(report["token_set"])


sys.path.insert(0, str(TERMUX))
import trmx_ai_lib  # noqa: E402

import contextlib  # noqa: E402
import importlib.machinery  # noqa: E402
import importlib.util  # noqa: E402
import io  # noqa: E402
import signal  # noqa: E402

# Load trmx-agent (no .py suffix) as a module for direct unit tests of
# the loop helpers. Importing it installs its SIGTERM handler in this
# process — restore the previous one; the unit tests never rely on it.
_loader = importlib.machinery.SourceFileLoader(
    "trmx_agent_mod", str(TERMUX / "trmx-agent"))
_spec = importlib.util.spec_from_loader("trmx_agent_mod", _loader)
trmx_agent = importlib.util.module_from_spec(_spec)
_old_sigterm = signal.getsignal(signal.SIGTERM)
_loader.exec_module(trmx_agent)
signal.signal(signal.SIGTERM, _old_sigterm)


def _make_agent(tmp: str) -> "trmx_agent.Agent":
    """An Agent over a throwaway home (no bridge server needed: the
    helpers under test either never call it or get a stub instead)."""
    home = Path(tmp)
    (home / "agent" / "tasks").mkdir(parents=True)
    (home / "agent" / "tasks" / "AT-unit.json").write_text(
        json.dumps({"instruction": "unit test"}))
    (home / "bridge.json").write_text(json.dumps({"token": "t", "port": 1}))
    return trmx_agent.Agent(home, "AT-unit")


class TestHistoryWorkingSet(unittest.TestCase):
    """audit §2-M1: last tool output full, older outputs one-liners."""

    def setUp(self):
        self.agent = _make_agent(tempfile.mkdtemp(prefix="trmx-hist-"))

    def test_last_tool_output_full_older_summarized(self):
        a = self.agent
        a.step = 1
        a._record_tool_result("list the files",
                              "file-one.txt\n" + "x" * 1000)
        a.step = 2
        a._record_tool_result("read the second one",
                              "second output body " + "y" * 500)
        text = a._history_text()
        # the LAST tool output survives far beyond the old 200-char cut
        self.assertIn("y" * 500, text)
        # the OLDER output is a one-line summary: head kept, tail gone
        self.assertIn("file-one.txt", text)
        self.assertNotIn("x" * 300, text)

    def test_single_tool_output_kept_full(self):
        a = self.agent
        a.step = 1
        a._record_tool_result("only step", "z" * 900)
        self.assertIn("z" * 900, a._history_text())

    def test_non_tool_entries_render_verbatim(self):
        a = self.agent
        a._history_add("step 1: said “hello there”")
        a.step = 2
        a._record_tool_result("do a thing", "output " + "q" * 300)
        text = a._history_text()
        self.assertIn("hello there", text)
        self.assertIn("q" * 300, text)


class _StubBridge:
    """Counts check() calls; serves one canned risk_tier (or fails)."""

    def __init__(self, tier: str = "safe", fail: bool = False):
        self.tier, self.fail, self.calls = tier, fail, 0

    def check(self, method, path, body=None, timeout=30):
        self.calls += 1
        if self.fail:
            raise trmx_agent.AgentError("bridge blip")
        return {"schema": {"risk_tier": self.tier}}


class TestRiskCache(unittest.TestCase):
    """audit §2-M2: the risk check is cached within one task run."""

    ARGS = {"tool": "some-tool"}

    def test_second_check_is_a_cache_hit(self):
        b, cache = _StubBridge("safe"), {}
        self.assertIsNone(trmx_agent.is_destructive(
            "submit_tool", self.ARGS, "run it", b, cache))
        self.assertIsNone(trmx_agent.is_destructive(
            "submit_tool", self.ARGS, "run it again", b, cache))
        self.assertEqual(b.calls, 1)
        self.assertEqual(cache, {"some-tool": "safe"})

    def test_confirm_tier_cached_and_reported(self):
        b, cache = _StubBridge("confirm"), {}
        for _ in range(2):
            self.assertEqual(trmx_agent.is_destructive(
                "submit_tool", self.ARGS, "run it", b, cache), "risk-tier")
        self.assertEqual(b.calls, 1)

    def test_seeded_cache_needs_no_fetch(self):
        b = _StubBridge("safe")
        self.assertEqual(trmx_agent.is_destructive(
            "submit_tool", self.ARGS, "run it", b,
            {"some-tool": "destructive"}), "risk-tier")
        self.assertIsNone(trmx_agent.is_destructive(
            "submit_tool", self.ARGS, "run it", b, {"some-tool": "safe"}))
        self.assertEqual(b.calls, 0)

    def test_fetch_failure_fails_closed_and_is_not_cached(self):
        b, cache = _StubBridge(fail=True), {}
        self.assertEqual(trmx_agent.is_destructive(
            "submit_tool", self.ARGS, "run it", b, cache), "risk-tier")
        self.assertEqual(cache, {})          # a blip is not a tier
        self.assertEqual(trmx_agent.is_destructive(
            "submit_tool", self.ARGS, "run it", b, cache), "risk-tier")
        self.assertEqual(b.calls, 2)         # retried live next step

    def test_no_cache_keeps_live_fetch_per_check(self):
        b = _StubBridge("safe")
        trmx_agent.is_destructive("submit_tool", self.ARGS, "run it", b)
        trmx_agent.is_destructive("submit_tool", self.ARGS, "run it", b)
        self.assertEqual(b.calls, 2)


class TestConfirmExpired(unittest.TestCase):
    """audit §2-M5: confirm timeout = distinct event, not a denial."""

    def setUp(self):
        self.agent = _make_agent(tempfile.mkdtemp(prefix="trmx-conf-"))

    def _ask(self, *a):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            got = self.agent.ask_confirm(*a)
        events = [json.loads(ln) for ln in buf.getvalue().splitlines()
                  if ln.startswith("{")]
        return got, events

    def test_timeout_returns_none_and_emits_confirm_expired(self):
        old = trmx_agent.CONFIRM_TIMEOUT_S
        trmx_agent.CONFIRM_TIMEOUT_S = 0
        try:
            got, events = self._ask("submit_tool", {"tool": "x"},
                                    "delete a thing", "destructive")
        finally:
            trmx_agent.CONFIRM_TIMEOUT_S = old
        self.assertIsNone(got)
        kinds = [e["t"] for e in events]
        self.assertEqual(kinds, ["confirm-needed", "confirm-expired"])
        self.assertEqual(events[-1]["step"], self.agent.step)
        self.assertFalse(self.agent._pending_path().exists())

    def test_deny_returns_false_without_expired_event(self):
        dp = self.agent._decision_path()
        dp.parent.mkdir(parents=True, exist_ok=True)
        dp.write_text(json.dumps({"decision": "deny"}))
        got, events = self._ask("submit_tool", {"tool": "x"},
                                "delete a thing", "destructive")
        self.assertFalse(got)
        self.assertEqual([e["t"] for e in events], ["confirm-needed"])

    def test_allow_returns_true_without_expired_event(self):
        dp = self.agent._decision_path()
        dp.parent.mkdir(parents=True, exist_ok=True)
        dp.write_text(json.dumps({"decision": "allow"}))
        got, events = self._ask("submit_tool", {"tool": "x"},
                                "delete a thing", "destructive")
        self.assertTrue(got)
        self.assertEqual([e["t"] for e in events], ["confirm-needed"])


def _action_shape(obj: dict) -> None:
    """Stand-in for trmx-agent's validate_action: the op whitelist and
    its mandatory companion field, nothing more."""
    need = {"say": "text", "done": "summary", "cannot": "reason"}.get(
        obj.get("op"))
    if obj.get("op") == "do":
        if not (isinstance(obj.get("tool"), str)
                and isinstance(obj.get("args"), dict)
                and isinstance(obj.get("describe"), str)):
            raise ValueError("do needs tool/args/describe")
    elif need is None or not isinstance(obj.get(need), str):
        raise ValueError("not an action object")


class TestExtractJson(unittest.TestCase):
    """Direct unit tests for trmx_ai_lib.extract_json (audit §2-M3)."""

    def test_first_block_default_unchanged(self):
        out = trmx_ai_lib.extract_json('pre {"a": 1} post {"b": 2}')
        self.assertEqual(out, {"a": 1})

    def test_no_validator_errors_unchanged(self):
        with self.assertRaises(ValueError):
            trmx_ai_lib.extract_json("no json at all")
        with self.assertRaises(ValueError):
            trmx_ai_lib.extract_json('{"a": 1')          # unbalanced
        with self.assertRaises(ValueError):
            trmx_ai_lib.extract_json('{not json} {"a": 1}')

    def test_prose_block_then_action_picks_action(self):
        text = ('thinking out loud {"op": "say"} — no wait, do this: '
                '{"op": "done", "summary": "real one"}')
        out = trmx_ai_lib.extract_json(text, validator=_action_shape)
        self.assertEqual(out, {"op": "done", "summary": "real one"})

    def test_last_valid_candidate_wins(self):
        text = ('{"op": "say", "text": "first"} '
                '{"op": "done", "summary": "second"}')
        out = trmx_ai_lib.extract_json(text, validator=_action_shape)
        self.assertEqual(out["op"], "done")

    def test_unparseable_block_skipped_with_validator(self):
        text = '{oops} {"op": "done", "summary": "ok"}'
        out = trmx_ai_lib.extract_json(text, validator=_action_shape)
        self.assertEqual(out["summary"], "ok")

    def test_none_valid_falls_back_to_first_block(self):
        # neither block is an action: current behaviour — first block,
        # parsed, returned for the caller to reject with its own error
        out = trmx_ai_lib.extract_json('{"a": 1} {"b": 2}',
                                       validator=_action_shape)
        self.assertEqual(out, {"a": 1})


class AgentConfirmTimeoutTest(AgentTestBase):
    # 5s: short enough for test_19, longer than the 3s task deadline
    # the inherited test_15d restarts with (there the TASK timeout must
    # still win over the confirm expiry).
    extra_env = {"TRMX_AGENT_CONFIRM_TIMEOUT_S": "5",
                 "TRMX_AGENT_TASK_TIMEOUT_S": "120"}

    def test_19_confirm_timeout_expires_distinctly(self):
        # audit §2-M5 end-to-end: nobody answers the confirm, so the
        # task must fail as TIMED OUT (not "denied by user") and the
        # stream must carry exactly one distinct confirm-expired event
        # alongside the single failed event — no double message.
        self.queue_responses(json.dumps(
            {"op": "do", "tool": "submit_tool",
             "args": {"tool": "test-noop", "args": {}},
             "describe": "delete the old cache files"}))
        t = self.create_task("clean up")
        got = self.wait_task(t["task_id"], {"failed"}, timeout=30)
        self.assertIn("confirmation timed out", got["error"])
        self.assertNotIn("denied", got["error"])
        events = self.agent_events(t["job_id"])
        kinds = [e["t"] for e in events]
        self.assertEqual(kinds.count("confirm-expired"), 1)
        self.assertEqual(kinds.count("failed"), 1)
        expired = next(e for e in events if e["t"] == "confirm-expired")
        needed = next(e for e in events if e["t"] == "confirm-needed")
        self.assertEqual(expired["step"], needed["step"])


class AgentStepCapTest(AgentTestBase):
    extra_env = {"TRMX_AGENT_MAX_STEPS": "3",
                 "TRMX_AGENT_TASK_TIMEOUT_S": "120"}

    def test_20_step_cap(self):
        self.queue_responses(*[
            json.dumps({"op": "say", "text": f"thinking {i}"})
            for i in range(10)])
        t = self.create_task("think forever")
        got = self.wait_task(t["task_id"], {"failed"}, timeout=90)
        self.assertIn("step cap", got["error"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
