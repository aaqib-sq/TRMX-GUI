# Phase-10 local prep — pairing-token hardening + log redaction

Branch: `phase10/token-and-logs` (local only — NOT pushed; zero GitHub writes per user order).
Prepared: 2026-10-16. Ready to push later as one PR into `main`.

## What changed (4 files)

1. `android/app/src/main/java/dev/trmx/gui/store/TokenStore.kt`
   - Pairing token moved from plain `MODE_PRIVATE` SharedPreferences into
     `EncryptedSharedPreferences` (AES256-GCM master key via Android Keystore,
     `AES256_SIV` key encryption / `AES256_GCM` value encryption).
   - One-time migration: on first init, a token found only in the legacy
     `trmx_store` prefs is copied into the encrypted store (`trmx_store_enc`)
     and the plaintext entry is deleted. Idempotent; runs in `init`.
   - Public API unchanged: `token`, `installBase`, `isPaired`, `clear()`,
     `generateToken()`, `bridgeBaseUrl` — all existing call sites (AppViewModel)
     work without modification. `installBase` stays plaintext (not a secret).
   - Known caveat (Phase-10 scope): encrypted-prefs creation does Keystore I/O
     on the calling thread (currently AppViewModel init). Move to
     Dispatchers.IO if first-paint timing ever matters.

2. `android/app/build.gradle.kts`
   - Added `implementation("androidx.security:security-crypto:1.1.0-alpha06")`.
     Project minSdk is 26 (≥ 23 required by this artifact), targetSdk 34.

3. `termux/trmx-bridge.py` (+ regenerated `termux/SHA256SUMS`)
   - Startup log no longer prints even the first 4 chars of an auto-generated
     token (`masked 'xxxx…'` removed); the message now only points at
     `trmx token --show`.
   - Checksums regenerated with `termux/gen_checksums.sh` (mandatory workflow
     whenever the bridge script changes).

## Redaction sweep (all sites audited)

- Android main sources: ZERO `Log.*`/`println` calls anywhere — nothing to redact.
- Bridge: no full request/response-body logging exists; auth failures never echo
  the presented bearer token (hmac.compare_digest; fixed error messages);
  `trmx token --show` displaying the token is deliberate user UX, kept.
- So the single site fixed above was the only actual secret-in-log instance.

## Verification (this machine)

- Python bridge suites via repo runner `sh termux/tests/run_tests.sh all`: EXIT 0
  (131 test lines ok across bridge/files/tools/bootstrap/ai/aicfg/services).
- `python3 tests/spec_conformance.py`: CONFORMANCE PASSED.
- Note: running suites individually in one shell (not via the runner) shows a
  pre-existing flaky isolation pattern for ai/aicfg — reproduced identically on
  pristine `main`, so it is NOT caused by this change.
- Android unit tests / Kotlin compile: NOT verifiable here — no JDK on this
  machine (`java` absent, `/usr/lib/jvm` absent). The repo's own ADR-006 names
  GitHub Actions CI as the Kotlin compile gate; the diff was statically
  reviewed (API signatures of EncryptedSharedPreferences 1.1.0-alpha06,
  MasterKey.Builder, SharedPreferences APIs; minSdk 26 ≥ 23 requirement).

## Remaining steps to publish (4 lines)

1. `git push -u origin phase10/token-and-logs` — open a PR into `main`.
2. Let GitHub Actions run the full Android unit suite on the branch (the
   compile gate this machine lacks).
3. Fix any CI compile issue (if none — merge the PR).
4. Confirm `main` CI is green after the merge; delete the branch.
