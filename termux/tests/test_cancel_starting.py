#!/usr/bin/env python3
"""Cancel-during-STARTING tests (audit §3-H5) — in-process.

Over HTTP the spawn window is microseconds wide, so this suite drives
JobManager directly with a gated create_subprocess_exec: the fake spawn
blocks on an event until the test has issued the cancel, then performs
the real spawn. The job must end CANCELLED with its process killed —
never clobbered back to RUNNING by the stale pre-spawn dict.

Run:  python3 termux/tests/test_cancel_starting.py  (or run_tests.sh cancel-starting)
stdlib only — ADR-002 rule.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import signal
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


def make_manager(tmp: str):
    home = Path(tmp) / "trmx-home"
    for sub in ("logs", "tools", "services"):
        (home / sub).mkdir(parents=True, exist_ok=True)
    (home / "bridge.json").write_text(json.dumps({}))
    config = MOD.Config(home / "bridge.json").load()
    store = MOD.Store(home / "jobs.db")
    policy = MOD.Policy(home)
    mgr = MOD.JobManager(home, store, config, policy, MOD.EventBus(),
                         MOD.ToolRegistry(home, policy))
    return home, store, mgr


def mkjob(store, home: Path, job_id: str, argv: list[str]) -> None:
    store.save_job({
        "job_id": job_id, "name": job_id, "type": "argv", "tool": None,
        "argv": argv, "script": None, "cwd": str(home), "env": None,
        "status": "QUEUED", "created_at": MOD.now_iso(), "started_at": None,
        "ended_at": None, "pid": None, "pgid": None, "exit_code": None,
        "signal": None, "cancel_requested": False, "cancel_reason": None,
        "error": None, "timeout_s": None, "progress_pct": None,
        "progress_detail": None, "stdout_bytes": 0, "stderr_bytes": 0,
        "log_seq": 0, "log_truncated": False, "idempotency_key": None,
    })


class TestCancelDuringStarting(unittest.TestCase):

    def run_scenario(self, cancel_midway: bool):
        """Drive one job through a gated spawn; optionally cancel in-window."""
        results = {}

        async def scenario() -> None:
            tmp = tempfile.mkdtemp(prefix="trmx-cancel-")
            home, store, mgr = make_manager(tmp)
            argv = ["sleep", "30"] if cancel_midway else ["/bin/true"]
            mkjob(store, home, "J-C1", argv)
            mgr._runtime("J-C1")
            gate = asyncio.Event()
            spawned: list = []
            real_spawn = asyncio.create_subprocess_exec

            async def gated_spawn(*a, **kw):
                await gate.wait()
                proc = await real_spawn(*a, **kw)
                spawned.append(proc)
                return proc

            asyncio.create_subprocess_exec = gated_spawn
            try:
                start_task = asyncio.create_task(mgr._start("J-C1"))
                # wait until the job is inside the STARTING spawn window
                for _ in range(500):
                    row = store.get_job("J-C1")
                    if row and row["status"] == "STARTING":
                        break
                    await asyncio.sleep(0.01)
                else:
                    raise AssertionError("job never entered STARTING")
                if cancel_midway:
                    await mgr.cancel("J-C1", None, False)
                    mid = store.get_job("J-C1")
                    assert mid["status"] == "CANCELLING", mid["status"]
                    assert mid["cancel_requested"] is True
                gate.set()
                await asyncio.wait_for(start_task, 5)
                # let _wait_exit (if any) settle the row
                for _ in range(500):
                    row = store.get_job("J-C1")
                    if row and row["status"] in MOD.TERMINAL_STATES:
                        break
                    await asyncio.sleep(0.01)
                results["row"] = store.get_job("J-C1")
                results["running"] = set(mgr.running)
                if spawned:
                    results["rc"] = await asyncio.wait_for(spawned[0].wait(), 5)
            finally:
                asyncio.create_subprocess_exec = real_spawn
                for p in spawned:      # never leave a stray sleep behind
                    try:
                        p.kill()
                    except Exception:  # noqa: BLE001
                        pass

        asyncio.run(scenario())
        return results

    def test_cancel_during_starting_kills_and_cancels(self) -> None:
        r = self.run_scenario(cancel_midway=True)
        row = r["row"]
        self.assertEqual(row["status"], "CANCELLED")
        self.assertEqual(row["cancel_reason"], "user")
        self.assertIsNone(row["pid"])            # never became a RUNNING job
        self.assertNotIn("J-C1", r["running"])
        # the spawned sleep was SIGKILLed, not left running for 30s
        self.assertEqual(r["rc"], -signal.SIGKILL)

    def test_no_cancel_starts_and_completes(self) -> None:
        r = self.run_scenario(cancel_midway=False)
        row = r["row"]
        self.assertEqual(row["status"], "COMPLETED")
        self.assertEqual(row["exit_code"], 0)
        self.assertEqual(r["rc"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
