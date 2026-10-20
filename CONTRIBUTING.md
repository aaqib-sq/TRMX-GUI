# Contributing to TRMX-GUI

Small repo, strict gates. The short version:

## Before you open a PR

```sh
sh termux/tests/run_tests.sh all     # the whole Python suite
python3 tests/spec_conformance.py    # app sources vs the frozen contracts
```

- Work from a fresh `origin/main` — fetch first, and read the
  compare-diff of your own PR before asking for review. Whole-file
  pushes from stale local trees are this repo's #1 self-inflicted
  wound (see `docs/RELEASE.md`, step 1).
- If you touched any file listed in `termux/SHA256SUMS`, regenerate it
  in the same PR: `sh termux/gen_checksums.sh` — from the verified
  tree only, and verify with `(cd termux && sha256sum -c SHA256SUMS)`.
- `termux/trmx-bridge.py` must stay ≤ 126976 bytes (CI size gate);
  shared machinery belongs in `termux/trmx_bridge_lib.py`.
- Wire changes follow PROTOCOL §13, in order: ADR in
  `docs/decisions/`, updated `fixtures/v1/`, a `CHANGELOG.md` entry —
  same commit. Capability changes update the `features` flags and the
  §13.1 compatibility matrix.
- Do not weaken a test assertion to make a suite pass. A version bump
  that moves an exact version expectation is an adaptation; a looser
  assertion is a regression.

## CI red?

Read **the red-run rule** in `docs/RELEASE.md` before changing code:
re-run once clean, then decide code-red vs infra-red from the table
there. Release steps (versioning, distribution, on-device
verification) live in the same document.

## Layout

`android/` the app · `termux/` the bridge, CLI, installer and Python
suites · `fixtures/v1/` the normative wire fixtures · `tests/` the
spec-conformance gate · `docs/` protocol, architecture, ADRs and the
on-device test guide.
