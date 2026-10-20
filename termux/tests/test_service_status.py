#!/usr/bin/env python3
"""Service status diagnosis + start-lock tests (audit §3-M1, §3-M9).

In-process: ServiceRegistry.status() is exercised against seeded job
rows with the module probes controlled per-case, the REAL /proc holder
scan is verified against a live listening socket owned by this process,
and two racing start() calls must yield exactly one submitted job.

Run:  python3 termux/tests/test_service_status.py  (or run_tests.sh service-status)
stdlib only — ADR-002 rule.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


def load_mod():
    spec = importlib.util.spec_from_file_location(
        "trmx_bridge", Path(__file__).resolve().parents[1] / "trmx-bridge.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


MOD = load_mod()
# Post-split the probes live in trmx_bridge_lib (ServiceRegistry's module);
# patch them where they are used, exactly as MOD was the use-site before.
LIB = sys.modules["trmx_bridge_lib"]

SCHEMA = {
    "id": "servetool", "name": "Serve Tool", "description": "test double",
    "binary": "servetool", "risk_tier": "safe", "fixed_argv": [],
    "args": [
        {"name": "port", "label": "Port", "type": "int", "min": 1024,
         "max": 65535, "default": 8000, "argv": ["--port", "{value}"]},
        {"name": "dir", "label": "Folder", "type": "path",
         "path_kind": "dir", "default": "~"},
    ],
}


def job_row(job_id: str, status: str, pid=None, pgid=None) -> dict:
    return {
        "job_id": job_id, "name": job_id, "type": "tool", "tool": "servetool",
        "argv": ["servetool"], "script": None, "cwd": "~", "env": None,
        "status": status, "created_at": MOD.now_iso(), "started_at": None,
        "ended_at": None, "pid": pid, "pgid": pgid, "exit_code": None,
        "signal": None, "cancel_requested": False, "cancel_reason": None,
        "error": None, "timeout_s": None, "progress_pct": None,
        "progress_detail": None, "stdout_bytes": 0, "stderr_bytes": 0,
        "log_seq": 0, "log_truncated": False, "idempotency_key": None,
    }


class FakeManager:
    """submit() double: slow (widens the start race), store-backed."""

    def __init__(self, store):
        self.store = store
        self.submits = 0

    async def submit(self, payload):
        self.submits += 1
        await asyncio.sleep(0.2)
        job_id = f"J-F{self.store.next_job_number()}"
        self.store.save_job(job_row(job_id, "RUNNING"))
        return self.store.get_job(job_id), False


def make_env(tmp: str, manager=None):
    home = Path(tmp) / "trmx-home"
    for sub in ("logs", "tools", "services"):
        (home / sub).mkdir(parents=True, exist_ok=True)
    (home / "tools" / "servetool.json").write_text(json.dumps(SCHEMA))
    store = MOD.Store(home / "jobs.db")
    policy = MOD.Policy(home)
    tools = MOD.ToolRegistry(home, policy)
    svc = MOD.ServiceRegistry(home, tools, manager, store, MOD.EventBus())
    return home, store, svc


def add_def(svc, sid: str = "web", args=None, port=None) -> None:
    d = {"id": sid, "name": sid.title(), "tool": "servetool",
         "args": {"port": 8123} if args is None else args,
         "autostart": False, "created_at": MOD.now_iso()}
    if port is not None:
        d["port"] = port
    svc.defs[sid] = d


class PatchedProbes:
    """Context manager: swap the module probes for controlled answers."""

    def __init__(self, alive=None, holder=None):
        self.alive, self.holder = alive, holder

    def __enter__(self):
        self._old = (LIB._pgid_alive, LIB._port_holder)
        if self.alive is not None:
            LIB._pgid_alive = lambda pgid: self.alive  # noqa: E731
        if self.holder is not None:
            LIB._port_holder = lambda port, job, store: self.holder  # noqa: E731
        return self

    def __exit__(self, *exc):
        LIB._pgid_alive, LIB._port_holder = self._old


class TestStatusClassification(unittest.TestCase):

    def setUp(self) -> None:
        self.tmp = tempfile.mkdtemp(prefix="trmx-svc-status-")
        self.home, self.store, self.svc = make_env(self.tmp)

    def test_process_dead(self) -> None:
        add_def(self.svc, args={})                    # no port anywhere
        self.store.save_job(job_row("J-D1", "RUNNING", pid=5150, pgid=5150))
        self.svc.bindings["web"] = "J-D1"
        with PatchedProbes(alive=False):
            st = self.svc.status("web")
        self.assertEqual(st["state"], "running")      # DB still says so
        self.assertIs(st["pid_alive"], False)
        self.assertEqual(st["diagnosis"], "process_dead")
        self.assertIn("pgid 5150", st["diagnosis_detail"])
        self.assertIsNone(st["port_open"])            # no port: not probed

    def test_healthy_running_self(self) -> None:
        add_def(self.svc)
        self.store.save_job(job_row("J-H1", "RUNNING", pid=4242, pgid=4242))
        self.svc.bindings["web"] = "J-H1"
        with PatchedProbes(alive=True, holder=(True, "self", None)):
            st = self.svc.status("web")
        self.assertEqual(st["state"], "running")
        self.assertIs(st["pid_alive"], True)
        self.assertIs(st["port_open"], True)
        self.assertEqual(st["port_holder"], "self")
        self.assertIsNone(st["diagnosis"])

    def test_stopped_failed_port_held_by_other_job(self) -> None:
        add_def(self.svc)
        self.store.save_job(job_row("J-F1", "FAILED", pid=1111, pgid=1111))
        self.svc.bindings["web"] = "J-F1"
        with PatchedProbes(holder=(True, "other_job", "J-OTHER")):
            st = self.svc.status("web")
        self.assertEqual(st["state"], "stopped")
        self.assertEqual(st["last_status"], "FAILED")
        self.assertEqual(st["diagnosis"], "port_in_use_by_other_job")
        self.assertIn("J-OTHER", st["diagnosis_detail"])
        self.assertIn("bind conflict", st["diagnosis_detail"])

    def test_port_held_by_unknown_process(self) -> None:
        add_def(self.svc)
        self.store.save_job(job_row("J-F2", "FAILED", pid=1111, pgid=1111))
        self.svc.bindings["web"] = "J-F2"
        with PatchedProbes(holder=(True, "unknown", None)):
            st = self.svc.status("web")
        self.assertEqual(st["diagnosis"], "port_in_use_by_unknown")
        self.assertIn("unknown process", st["diagnosis_detail"])

    def test_port_free_no_diagnosis(self) -> None:
        add_def(self.svc)
        with PatchedProbes(holder=(False, "unknown", None)):
            st = self.svc.status("web")
        self.assertIs(st["port_open"], False)
        self.assertIsNone(st["diagnosis"])

    def test_explicit_port_beats_args_port(self) -> None:
        add_def(self.svc, args={"port": 8123}, port=19001)
        with PatchedProbes(holder=(False, "unknown", None)):
            st = self.svc.status("web")
        self.assertEqual(st["port"], 19001)

    def test_def_port_validation(self) -> None:
        base = {"id": "ok", "name": "OK", "tool": "servetool", "args": {}}
        for bad in (0, 65536, "8123", True):
            with self.assertRaises(ValueError, msg=f"port={bad!r}"):
                self.svc._validate_def({**base, "port": bad})
        self.svc._validate_def({**base, "port": 8080})     # valid: no raise
        self.svc._validate_def(base)                        # absent is fine


class TestRealProbes(unittest.TestCase):
    """/proc-backed probes against real processes (Linux)."""

    def setUp(self) -> None:
        self.tmp = tempfile.mkdtemp(prefix="trmx-probes-")
        self.home, self.store, self.svc = make_env(self.tmp)
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(1)
        self.port = self.sock.getsockname()[1]

    def tearDown(self) -> None:
        self.sock.close()

    def test_holder_unknown_when_no_job_owns_it(self) -> None:
        opened, kind, holder = LIB._port_holder(self.port, None, self.store)
        self.assertIs(opened, True)
        self.assertEqual((kind, holder), ("unknown", None))

    def test_holder_self_and_other_job(self) -> None:
        # this test process holds the socket; register it as an ACTIVE job
        self.store.save_job(job_row("J-ME", "RUNNING",
                                    pid=os.getpid(), pgid=os.getpid()))
        opened, kind, holder = LIB._port_holder(self.port, None, self.store)
        self.assertEqual((opened, kind, holder), (True, "other_job", "J-ME"))
        bound = self.store.get_job("J-ME")
        opened, kind, holder = LIB._port_holder(self.port, bound, self.store)
        self.assertEqual((opened, kind, holder), (True, "self", None))

    def test_free_port(self) -> None:
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        free = s.getsockname()[1]
        s.close()
        opened, kind, holder = LIB._port_holder(free, None, self.store)
        self.assertIs(opened, False)
        self.assertEqual((kind, holder), ("unknown", None))

    def test_pgid_alive(self) -> None:
        self.assertTrue(LIB._pgid_alive(os.getpgrp()))
        proc = subprocess.Popen(["sleep", "30"], start_new_session=True)
        try:
            self.assertTrue(LIB._pgid_alive(proc.pid))
        finally:
            proc.kill()
            proc.wait()
        self.assertFalse(LIB._pgid_alive(proc.pid))
        self.assertFalse(LIB._pgid_alive(None))


class TestDoubleStart(unittest.TestCase):
    """§3-M9: two racing starts under the per-service lock -> one job."""

    def test_double_start_yields_one_process(self) -> None:
        async def scenario():
            tmp = tempfile.mkdtemp(prefix="trmx-race-")
            home, store, _ = make_env(tmp)
            fake = FakeManager(store)
            policy = MOD.Policy(home)
            svc = MOD.ServiceRegistry(home, MOD.ToolRegistry(home, policy),
                                      fake, store, MOD.EventBus())
            add_def(svc)
            results = await asyncio.gather(
                svc.start("web"), svc.start("web"), return_exceptions=True)
            return fake, results

        fake, results = asyncio.run(scenario())
        oks = [r for r in results if isinstance(r, dict)]
        errs = [r for r in results if isinstance(r, MOD.BridgeError)]
        self.assertEqual(len(oks), 1, results)
        self.assertEqual(len(errs), 1, results)
        self.assertEqual(errs[0].status, 409)
        self.assertEqual(errs[0].code, "SERVICE_RUNNING")
        self.assertEqual(fake.submits, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
