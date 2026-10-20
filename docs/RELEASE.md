# Release process & the red-run rule

How a change gets from a branch to a shipped TRMX-GUI build — and how to
tell a real failure from a flaky one on the way. Written after the
2026-10-19/20 audit (§7-M1/L2): before this document there was no
release process written down anywhere, and two stillborn CI runs were
misread as code failures.

## Version story

Versions are a triple — **app** (`versionName` in
`android/app/build.gradle.kts`) / **bridge** (`VERSION` in
`termux/trmx-bridge.py`) / **protocol** (TRMX-P/1 in
`docs/PROTOCOL.md`, major in the `X-TRMX-Protocol` header, minor
additive). The current line is **bridge 0.6.0, protocol 1.1**; the
app ↔ bridge compatibility matrix is PROTOCOL §13.1 — a bridge bump
that adds routes must update it, the `features` flags in
`GET /v1/system/info`, and CHANGELOG.md in the same commit
(PROTOCOL §13 governance).

## The red-run rule (code-red vs infra-red)

**After any red CI run, re-run it once clean before touching any code.**
A single red run proves nothing; the interesting question is *which
kind* of red it is.

| Signal | Code-red (fix the code) | Infra-red (re-run; do not chase) |
|---|---|---|
| Where it fails | A compile step, a named test, `spec_conformance.py`, the Termux suites, the sums/size gates | Runner provisioning, checkout, Gradle distribution download, cache-service errors, artifact upload |
| Log content | Compiler errors, assertion failures, a test name — reproducible text | Network/HTTP noise (400/403/5xx from caches or CDNs), "service unavailable", timeouts before any project code ran |
| Re-run on the same commit | Fails again at the same step | Goes green, or fails somewhere unrelated |
| Local reproduction | `sh termux/tests/run_tests.sh all` or `python3 tests/spec_conformance.py` fails the same way | Local gates are green |

Rules of thumb, each bought with a real incident:

- A **double-red whose annotations only show cache-service 400
  warnings is not proof of a code fault** (PR #20: the first two runs
  looked infra-red; the actual code fault — a stale-tree push — was
  proven by a compare-diff plus a green control run, not by the red).
- **Unauthenticated log fetches return 403.** That is a permissions
  fact, not a CI result. The signed-URL workaround is in
  `docs/ci/README.md`.
- If it is infra-red: re-run, note "infra-red, green on re-run" on the
  PR, and move on. Never change code to appease an infra-red run — the
  next run cannot tell you whether your "fix" did anything.
- If it is code-red: fix forward on the same branch; the PR description
  states which gate failed and the local command that reproduces it.

## Release steps

1. **Start from the real `main`.** `git fetch origin` first; never
   build a release (or regenerate checksums) from a local tree you
   have not compared against `origin/main`. The stale-tree trap —
   pushing a whole file edited from an old copy — caused two
   incidents in one week (audit §7 hardening proposal).
2. **Run every gate locally:**
   ```sh
   sh termux/tests/run_tests.sh all        # bridge/files/tools/ai/services/agent/bootstrap/…
   python3 tests/spec_conformance.py       # must print CONFORMANCE PASSED
   (cd termux && sha256sum -c SHA256SUMS)  # after step 3
   wc -c termux/trmx-bridge.py             # must stay ≤ 126976 (CI size gate)
   ```
   The Android half (assembleDebug + JVM unit tests) can only run in
   CI from this workspace (ADR-006) — that is what the `android` CI job
   is for; the `termux` job runs the three checks above.
3. **Version + changelog + protocol, one commit.** Bump the bridge
   and/or app version for whatever changed, add the CHANGELOG section
   for the new version (never leave everything under `[Unreleased]`),
   and update PROTOCOL.md + fixtures if the wire changed (§13 order:
   ADR → fixtures → changelog).
4. **Checksums last, from the verified tree only.** After step 1's
   comparison and step 3's edits: `sh termux/gen_checksums.sh`, then
   the `sha256sum -c` check from step 2. SHA256SUMS certifies whatever
   tree it was generated in — generating it from an unverified tree
   certifies the wrong bits faithfully.
5. **PR, then read the compare-diff.** The merged delta must be
   *exactly* the intended change. Both CI jobs green (red-run rule
   applies). Merge, and delete the branch (the repo auto-deletes head
   branches; stale merged branches are raw material for step 1's trap).
6. **Distribute.**
   - App: the debug APK artifact of the green `android` run. Debug
     builds are signed with the builder's local debug key — **no
     keystore is committed** (audit §4-H2). A phone that has TRMX
     installed under a *different* signature (the retired committed
     key, or another machine's debug key) refuses the update:
     uninstall once, then install, then re-pair. Builds from the same
     signer update in place.
   - Bridge: the app setup / Re-run setup flow (or the one-paste
     install line) fetches `termux/install.sh` from `main`; the
     installer verifies SHA256SUMS and fails closed, preserves
     `bridge.json` (pairing survives), and restarts a running bridge.
7. **Verify on device before calling it shipped.** Walk the current
   round in `docs/ON-DEVICE-TEST.md` on a real phone. CI green is a
   code statement; the device round is the product statement — the
   project has been bitten by flows that passed every suite and failed
   only on the phone (pairing, storage policy).
