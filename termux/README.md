# termux/ — Termux-side components

**Status: Phase 3 (bootstrap automation) delivered and sandbox-verified — see [CHANGELOG](../CHANGELOG.md) and [ADR-005](../docs/decisions/ADR-005-bootstrap-automation.md).**

```
termux/
  trmx-bridge.py       # the daemon — Python 3, STDLIB ONLY (TRMX-P/1 subset)
  trmx_bridge_lib.py   # policy + tool/service registries (split out of the daemon, §7-H2)
  trmx                 # control CLI v0.3.0: init | pair | unpair | token | start | stop |
                       #   status | pid | enable-boot | enable-service | version
  install.sh           # one-command installer (intent-driven; --source DIR for local installs)
  gen_checksums.sh     # regenerates SHA256SUMS — run after any bridge/CLI change
  SHA256SUMS           # pinned digests of trmx-bridge.py + trmx
  tests/
    test_bridge.py     # 19-test bridge suite (real TCP, real subprocess)
    test_bootstrap.py  # 12-test bootstrap suite (installer + lifecycle commands)
    test_files.py      # 17-test file-endpoint suite (PROTOCOL §6)
    run_tests.sh       #   → sh termux/tests/run_tests.sh [bridge|files|tools|bootstrap|all]
    smoke.sh           # the human-visible Phase 2 demo
    intent_commands.sh # prints the adb commands for all 8 control-plane ops
  tools/               # Tool Registry schemas (Phase 9)
```

## Install (what the Android app will do for you in Phase 4)

```sh
pkg install python curl
sh install.sh                  # remote mode: fetches from GitHub raw + verifies SHA256SUMS
# or, from a checkout:
sh install.sh --source /path/to/TRMX-GUI/termux
~/.trmx/trmx start             # bridge on 127.0.0.1:27342
```

Optional lifecycle (both off by default):

```sh
sh install.sh --enable-boot    # one-shot opt-in during install (flag works
                               # with --source / --base too), or any time:
~/.trmx/trmx enable-boot       # start at boot via Termux:Boot (install that app, open once)
pkg install termux-services    # then:
~/.trmx/trmx enable-service    # runit service: start at boot + auto-restart on crash
```

**Reboot / phantom-kill chain** (audit §5-H3) — how the bridge comes back
after Android kills Termux or the phone reboots:

1. `install.sh --enable-boot` (or `trmx enable-boot`) installs
   `~/.termux/boot/trmx-bridge`, which Termux:Boot runs at boot to
   `trmx start` the bridge. Termux:Boot itself must be installed and
   opened once, or Android will not let it run at boot.
2. While it serves, the bridge touches `~/.trmx/heartbeat` every 30 s.
   Port closed + heartbeat older than ~2 min = the bridge died (it is
   safe to restart); a fresh heartbeat + closed port = it is still
   starting. `jobs.db` reconciliation on the next start re-adopts or
   marks LOST whatever was running.
3. In the app, the Dashboard status strip reads **“Bridge is down”**
   (with the Repair card) whenever the bridge is not answering, so a
   silent death is visible instead of looking like a hang.

- Runtime dependency: `pkg install python` — nothing else, ever (stdlib-only rule, ADR-002).
- The installer is idempotent; upgrading a running bridge stops it, replaces
  files, and restarts it automatically.
- On Termux the bridge is strict (executable allowlist + cwd roots); off-Termux (CI) it is
  dev-lenient so the tests can run on any Linux box.
- Intent contract for the app: [docs/CONTROL-PLANE.md](../docs/CONTROL-PLANE.md).
- Implemented TRMX-P/1 subset + deviations: [ADR-004](../docs/decisions/ADR-004-phase2-poc.md).

## Retention (audit §6-H2/H3, §3-M14)

The bridge keeps job state, frame logs and audit rows on phone storage, so
it prunes them under one policy, implemented in
`JobManager.apply_retention()` / `_schedule_runtime_eviction()` in
`trmx-bridge.py`. All limits are tunables in `~/.trmx/bridge.json`
(`DEFAULT_CONFIG`):

| What | Rule | Default |
| --- | --- | --- |
| Finished-job memory (`runtimes`) | entry evicted after a grace period once the job is terminal; the on-disk frame log keeps `/output` replay working | `runtime_evict_grace_s` = 300 |
| Terminal job rows + `logs/<job_id>/` | pruned when `ended_at` is older than the limit, at bridge start | `retention_days` = 30 |
| Terminal-job floor | the newest terminal jobs are ALWAYS kept, whatever their age | `retention_keep_terminal` = 50 |
| Audit table | only the newest rows kept, at bridge start | `audit_max_rows` = 5000 |
| Agent task files (`agent/tasks/`, plus their `agent/pending/` confirm file) | pruned when older than the TTL, at bridge start | `agent_task_ttl_days` = 30 |

Never pruned: ACTIVE (QUEUED/STARTING/RUNNING/CANCELLING) jobs and their
logs, agent task files whose job is still ACTIVE, and files the bridge
cannot read (kept for a human to see). The prune counts of each start are
written to the bridge log (`retention: pruned {...}`).
