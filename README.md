# TRMX-GUI

**A visual control plane for the Linux living in your Android phone.**

> The app is the face. **Termux is the engine — a complete Linux userspace on the
> device.** `Android UI → control layer → Termux → shell · Python · git · servers · archives · CLI tools · AI`
>
> TRMX-GUI does not replace Termux — it makes it operable without typing.

## What it does

- **Jobs** — run anything with live output, in the background, with progress you
  can watch and a Stop button that actually stops.
- **AI agent** — describe what you want in plain words; the agent plans the
  steps, executes them through the bridge, shows each step as it happens, and
  **asks before anything destructive** (silence = no). Local model or cloud API —
  your choice, shown on screen.
- **Tools** — any CLI tool (yt-dlp, ffmpeg, git, …) gets a calm form UI generated
  from a JSON schema. Drop a schema in `~/.trmx/tools/` and it becomes a
  first-class screen — no app changes.
- **Files** — browse, create, rename, move and delete inside Termux storage.
- **Services** — define and control background services (start/stop/restart,
  boot autostart) from switch rows.
- **One-screen setup** — first run is a single screen and one button; the app
  walks you to the two permission toggles Android/Termux require and does the
  rest itself. One-tap repair in Settings.

Pairing is token-authenticated over loopback only; the token lives in the app's
private storage. Keystore-encrypted storage and token-free logs shipped with
Phase 10 (merged — see status below). Design decisions are recorded as ADRs
under [docs/decisions/](docs/decisions/).

## Project status

| Area | State |
|---|---|
| Bridge daemon + CLI (`termux/`) | ✅ Built, spec'd (TRMX-P/1), fully tested |
| Android app (`android/`) | ✅ Built, CI compiles + unit-tests green |
| Agent task loop | ✅ Built & tested (scripted backend); **real-model validation pending** |
| Setup / pairing | ✅ One-screen flow built; **on-device round pending** |
| Phase 10 hardening | ✅ Merged (PR #4) — encrypted token store, log redaction |
| On-device verification | ⏳ The remaining rounds need a real phone + Termux |
| Release APK | ⏳ Not cut yet |

## Documentation

- [docs/PROTOCOL.md](docs/PROTOCOL.md) — **TRMX-P/1**: the wire contract between
  app and bridge (routes, error codes, events, limits, versioning)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — living architecture summary
- [docs/CONTROL-PLANE.md](docs/CONTROL-PLANE.md) — the frozen `RUN_COMMAND`
  intent interface
- [docs/AI-CONFIG.md](docs/AI-CONFIG.md) — configuring the agent/AI backend
  (local CLI model or cloud API)
- [docs/decisions/](docs/decisions/) — ADR decision log (ADR-001…015)
- [docs/PHASE-0-DISCOVERY-REPORT.md](docs/PHASE-0-DISCOVERY-REPORT.md) —
  feasibility, threat model, full roadmap
- [fixtures/v1/](fixtures/v1/) — normative contract fixtures shared by both
  test suites

## Repository layout

```
docs/        protocol spec, architecture, control-plane spec, ADRs
fixtures/    TRMX-P/1 contract fixtures (shared by both test suites)
android/     native app (Kotlin + Jetpack Compose)
termux/      bridge daemon, trmx CLI, installer, agent service, tests
tests/       spec-conformance check (Kotlin sources vs frozen contracts)
```

## Try it

```sh
pkg install python curl          # in Termux; elsewhere python3 + curl suffice
git clone https://github.com/aaqib-sq/TRMX-GUI && cd TRMX-GUI

# install the bridge into a throwaway home and start it
D=/tmp/trmx-demo; mkdir -p $D
env HOME=$D TRMX_HOME=$D/.trmx sh termux/install.sh --source termux
env HOME=$D TRMX_HOME=$D/.trmx $D/.trmx/trmx start
env HOME=$D TRMX_HOME=$D/.trmx $D/.trmx/trmx status    # → data-plane: UP
env HOME=$D TRMX_HOME=$D/.trmx $D/.trmx/trmx stop

sh termux/tests/run_tests.sh all   # full Python suite (bridge/files/tools/ai/services/agent/…)
python3 tests/spec_conformance.py  # app sources vs frozen contracts
```

## Building the app

```sh
cd android && gradle :app:assembleDebug          # JDK 17, Gradle 8.7
gradle :app:testDebugUnitTest                    # JVM unit tests
```

CI (`.github/workflows/android.yml`) runs the conformance check, the debug
build and the unit tests on every push and PR. The debug APK is uploaded as a
build artifact.

## License

MIT — see [LICENSE](LICENSE). [CHANGELOG.md](CHANGELOG.md) tracks releases.
