#!/usr/bin/env python3
"""Retention tests (audit §6-H2/H3, §3-M14) — in-process, no bridge server.

Covers JobManager.apply_retention() (bridge-start pruning of terminal job
rows + log dirs, audit-table cap, agent task files) and the grace eviction
of finished-job runtimes, with the protection rules front and center:
ACTIVE jobs are never pruned, and the newest N terminal jobs always
survive whatever their age.

Run:  python3 termux/tests/test_retention.py   (or tests/run_tests.sh retention)
stdlib only — ADR-002 rule.
"""

from __future__ import annotations

import asyncio
import datetime as dt
import importlib.util
import json
import os
import tempfile
import time
import unittest
from pathlib import Path


def load_mod():
    spec = importlib.util.spec_from_file_location(
        "trmx_bridge", Path(__file__).resolve().parents[1] / "trmx-bridge.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


MOD = load_mod()


def iso_days_ago(days: float) -> str:
    t = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=days)
    return t.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def make_manager(tmp: str, cfg: dict | None = None):
    home = Path(tmp) / "trmx-home"
    for sub in ("logs", "tools", "services", "agent/tasks", "agent/pending"):
        (home / sub).mkdir(parents=True, exist_ok=True)
    (home / "bridge.json").write_text(json.dumps(cfg or {}))
    config = MOD.Config(home / "bridge.json").load()
    store = MOD.Store(home / "jobs.db")
    policy = MOD.Policy(home)
    mgr = MOD.JobManager(home, store, config, policy, MOD.EventBus(),
                         MOD.ToolRegistry(home, policy))
    return home, store, mgr


def mkjob(store, home: Path, job_id: str, status: str,
          created_days_ago: float, ended_days_ago: float | None = None) -> None:
    store.save_job({
        "job_id": job_id, "name": job_id, "type": "argv", "tool": None,
        "argv": ["/bin/true"], "script": None, "cwd": str(home), "env": None,
        "status": status, "created_at": iso_days_ago(created_days_ago),
        "started_at": iso_days_ago(created_days_ago),
        "ended_at": (iso_days_ago(ended_days_ago)
                     if ended_days_ago is not None else None),
        "pid": None, "pgid": None, "exit_code": 0, "signal": None,
        "cancel_requested": False, "cancel_reason": None, "error": None,
        "timeout_s": None, "progress_pct": None, "progress_detail": None,
        "stdout_bytes": 0, "stderr_bytes": 0, "log_seq": 0,
        "log_truncated": False, "idempotency_key": None,
    })


class TestRetentionPrune(unittest.TestCase):
    """apply_retention(): age + keep-floor + ACTIVE protections."""

    def setUp(self) -> None:
        self.tmp = tempfile.mkdtemp(prefix="trmx-ret-")
        self.home, self.store, self.mgr = make_manager(self.tmp)

    def job_ids(self) -> set[str]:
        rows = self.store.db.execute("SELECT job_id FROM jobs").fetchall()
        return {r["job_id"] for r in rows}

    def test_prune_respects_age_floor_and_active(self) -> None:
        # 61 terminal jobs (60 ended 40 days ago + 1 ended yesterday): the
        # newest 50 terminal jobs by insertion always survive the floor, so
        # exactly the oldest 11 go.
        for i in range(1, 61):
            mkjob(self.store, self.home, f"J-T{i:02d}", "COMPLETED", 41, 40)
        # a recently-ended terminal job (age rule) + old ACTIVE jobs
        mkjob(self.store, self.home, "J-RECENT", "FAILED", 2, 1)
        mkjob(self.store, self.home, "J-RUNNING", "RUNNING", 40)
        mkjob(self.store, self.home, "J-QUEUED", "QUEUED", 40)
        # log dirs: J-T05 will be pruned, J-T55 will survive the floor
        for jid in ("J-T05", "J-T55"):
            d = self.home / "logs" / jid
            d.mkdir(parents=True)
            (d / "frames.jsonl").write_text("{}\n")
        # audit: 6001 rows -> capped at 5000 newest
        self.store.db.executemany(
            "INSERT INTO audit(ts, kind, detail) VALUES(?,?,?)",
            [(iso_days_ago(40), "k", f"row {i}") for i in range(6001)])
        self.store.db.commit()
        # agent task files (mtime drives the TTL)
        tasks = self.home / "agent" / "tasks"
        pending = self.home / "agent" / "pending"
        old = time.time() - 40 * 86400
        for name, body in (
                ("AT-old-done", {"status": "done", "job_id": None}),
                ("AT-old-gonejob", {"status": "done", "job_id": "J-GONE"}),
                ("AT-old-active", {"status": "running", "job_id": "J-RUNNING"}),
                ("AT-new", {"status": "done", "job_id": None})):
            p = tasks / f"{name}.json"
            p.write_text(json.dumps(body))
            if name != "AT-new":
                os.utime(p, (old, old))
        (pending / "AT-old-done.json").write_text("{}")
        os.utime(pending / "AT-old-done.json", (old, old))

        counts = self.mgr.apply_retention()

        self.assertEqual(counts["jobs"], 11)
        self.assertEqual(counts["log_dirs"], 1)
        self.assertEqual(counts["audit_rows"], 1001)   # 6001 rows -> 5000 cap
        self.assertEqual(counts["agent_tasks"], 2)
        ids = self.job_ids()
        self.assertEqual(len(ids), 52)          # 50 floor-kept + 2 active
        for i in range(1, 12):
            self.assertNotIn(f"J-T{i:02d}", ids)
        for i in range(12, 61):
            self.assertIn(f"J-T{i:02d}", ids)
        self.assertIn("J-RECENT", ids)
        self.assertIn("J-RUNNING", ids)         # ACTIVE: never pruned
        self.assertIn("J-QUEUED", ids)
        self.assertFalse((self.home / "logs" / "J-T05").exists())
        self.assertTrue((self.home / "logs" / "J-T55" / "frames.jsonl").exists())
        n_audit = self.store.db.execute("SELECT COUNT(*) c FROM audit").fetchone()["c"]
        self.assertEqual(n_audit, 5000)
        oldest = self.store.db.execute(
            "SELECT detail FROM audit ORDER BY id LIMIT 1").fetchone()["detail"]
        self.assertEqual(oldest, "row 1001")    # the newest 5000 survived
        self.assertFalse((tasks / "AT-old-done.json").exists())
        self.assertFalse((pending / "AT-old-done.json").exists())
        self.assertFalse((tasks / "AT-old-gonejob.json").exists())
        self.assertTrue((tasks / "AT-old-active.json").exists())  # job ACTIVE
        self.assertTrue((tasks / "AT-new.json").exists())          # within TTL

    def test_prune_is_idempotent(self) -> None:
        mkjob(self.store, self.home, "J-T01", "COMPLETED", 41, 40)
        first = self.mgr.apply_retention()
        second = self.mgr.apply_retention()
        self.assertEqual(first["jobs"], 0)      # keep-floor 50 protects it
        self.assertEqual(second, {"jobs": 0, "log_dirs": 0,
                                  "audit_rows": 0, "agent_tasks": 0})


class TestRuntimeEviction(unittest.TestCase):
    """_finalize() schedules eviction; running jobs' runtimes stay."""

    def test_eviction_after_grace(self) -> None:
        async def scenario() -> None:
            tmp = tempfile.mkdtemp(prefix="trmx-evict-")
            home, store, mgr = make_manager(tmp, {"runtime_evict_grace_s": 0.05})
            mkjob(store, home, "J-E1", "QUEUED", 0)
            mkjob(store, home, "J-E2", "RUNNING", 0)
            mgr._runtime("J-E1")
            mgr._runtime("J-E2")
            mgr._finalize("J-E1", "COMPLETED")
            # grace has not elapsed: the entry (and live followers) remain
            self.assertIn("J-E1", mgr.runtimes)
            await asyncio.sleep(0.4)
            self.assertNotIn("J-E1", mgr.runtimes)
            self.assertIn("J-E2", mgr.runtimes)     # running job untouched
            # the frame log survives on disk: /output replay still works
            self.assertTrue((home / "logs" / "J-E1" / "frames.jsonl").is_file())

        asyncio.run(scenario())

    def test_finished_tasks_drop_out_of_rt_tasks(self) -> None:
        async def scenario() -> None:
            tmp = tempfile.mkdtemp(prefix="trmx-track-")
            home, store, mgr = make_manager(tmp)
            rt = mgr._runtime("J-E3")
            task = mgr._track(rt, asyncio.sleep(0))
            self.assertIn(task, rt.tasks)
            await task
            await asyncio.sleep(0)      # let the done callback run
            self.assertEqual(rt.tasks, set())

        asyncio.run(scenario())


if __name__ == "__main__":
    unittest.main(verbosity=2)
