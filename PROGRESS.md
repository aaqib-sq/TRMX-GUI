# TRMX Compact Redesign Progress & Audit

Branch: `agy/trmx-compact`
Base Branch: `agy/trmx-rebuild` (commit `4f3f5a2`)
Date: 2026-10-23
Design Goal: Fully compact, streamlined, and fast Termux visual companion with limited features, zero bloat, and pure OLED dark aesthetics.

---

## 1. Keep / Cut Decision List & Rationales

The owner's goal was clear: *"the app is far too complex, and he wants it fully COMPACT with LIMITED features."*

Prior to this redesign, TRMX-GUI had devolved into an unwieldy kitchen sink with 14+ visual destinations, 5 ViewModels, nested overlays, root layers, recipe export/import systems, background service supervisors, an AI schema generator, and a multi-pane file manager duplicating Android and Linux capabilities.

### Features CUT and Why:
1. **Service Daemon Supervisor (`ServicesScreen.kt`, autostart toggles, PID/port probing)**
   - *Why Cut*: Mobile phones should not be treated as high-complexity server rack supervisors. Termux already provides `termux-services` and standard background execution. Managing systemd-like daemon definitions inside an Android companion app added immense state machinery, REST endpoints, and UI confusion.
2. **AI Schema Builder (`SchemaBuilderScreen.kt`)**
   - *Why Cut*: Prompting an LLM to generate JSON tool schemas inside an Android app is an esoteric gimmick. CLI tool schemas are declarative JSON files that are created once and stored in `~/.trmx/tools/`.
3. **Recipe System & Launcher Shortcuts (`RecipeStore.kt`, JSON file export/import, ShortcutManager)**
   - *Why Cut*: Storing tool parameter presets as "recipes", exporting them as files, importing them through Android file pickers, and generating launcher shortcut intents created massive architectural tangles across `MainActivity`, `FilesViewModel`, and `ToolboxScreen`. A compact app lets users directly execute commands or recall recent runs from activity history.
4. **Heavyweight File Explorer (`FilesScreen.kt` full multi-pane manager)**
   - *Why Cut*: Duplicating a full file manager (with directory creation, renaming, recursive deletion, upload staging, download caches, and pagination) inside a Termux companion is redundant. Android has its native Files app, and Termux has standard shell utilities (`ls`, `mv`, `rm`). We replaced this by directly surfacing output artifacts on completed job cards so users can open or share generated files (e.g. videos from `yt-dlp`) with one tap.
5. **Bridge Diagnostics Sheet (`DiagnosticsSheet.kt`)**
   - *Why Cut*: Showing raw process RSS memory bytes, PID numbers, and bridge socket counters created developer visual clutter in what should be a clean, minimal user experience. A simple connection indicator ("Connected" / "Bridge unreachable") and clean daemon control in Settings is far cleaner.
6. **Multi-Provider AI Hub (`AiConfigViewModel.kt`, 6 LLM providers, API keys, custom endpoints)**
   - *Why Cut*: Managing API keys and model configurations across 6 cloud providers in the mobile UI is unnecessary when Termux environment variables and local configs already manage backend tools cleanly.
7. **11-Surface Navigation Labyrinth (3 Tabs + 4 Overflows + 2 Overlays + 2 Root Layers + Subsheets)**
   - *Why Cut*: Extreme cognitive overload. Users had to remember whether a destination was a bottom tab, an overflow menu item, a full-screen overlay, or a root layer above another screen.

### Features KEPT and Elevated:
1. **Unified Command Runner & Tools Console (`RunScreen.kt`)**:
   - Direct Shell Execution: Type any Termux command (e.g. `uname -a`, `python app.py`, `git pull`, `pkg update`) and hit "Run in Termux" for instant 1-tap execution.
   - Quick Inspection Chips: 1-tap suggestions for common commands (`uname -a`, `uptime`, `df -h`, `pkg list-installed`, `whoami`).
   - Discovered Tools: Clean, responsive cards for schemas found in `~/.trmx/tools/` with binary path and "Ready" badge or "Install" button.
2. **Live Execution Monitor & Activity History (`ActivityScreen.kt`)**:
   - Running Now Section: Live pulsing status badge, elapsed timer, and an instant red "Stop" button right on the card for quick cancellation without opening detail.
   - Recent Jobs History: Compact rows with status badges (`DONE`, `FAILED`, `CANCELLED`), relative timestamp (`2 min ago`), duration, and exit code (`exit 0`).
   - Direct drill-down: Tapping any active or recent job immediately opens the live terminal log viewer.
3. **Live Streaming Terminal Log Viewer (`JobDetailScreen.kt`)**:
   - Monospace terminal viewer with true black background and auto-scroll.
   - 1-tap "Copy Output" button that copies full stdout/stderr to the Android clipboard.
   - Stop/Cancel button with confirmation dialog for destructive jobs.
   - Direct artifact open/share chips.
4. **Streamlined Tool Parameter Form (`ToolFormScreen.kt`)**:
   - Generated dynamically from tool schemas (`FormEngine`).
   - Form inputs (text, numbers, booleans, choices, sliders).
   - Clean argv command preview strip.
   - Sticky primary "Run Tool" button that launches the job and immediately switches to the live output view.
5. **Minimalist Settings & Pairing (`SettingsScreen.kt` & `ConnectScreen.kt`)**:
   - Connection status pill (Green "Connected" / Red "Offline") + loopback address (`http://127.0.0.1:8080`).
   - "Test Connection" button.
   - "Stop Bridge" daemon control with confirmation.
   - "Re-pair App" action that resets token to reconnect.

---

## 2. Redesign Architecture: Before vs. After

| Attribute | Before (Bloated) | After (Compact Redesign) |
|---|---|---|
| **Bottom Navigation** | 3 Tabs (Agent, Tools, Work) | **2 Tabs (Run, Activity)** |
| **Top Bar** | Header with 4-item overflow menu (Files, Services, Diagnostics, Settings) | **Minimalist OLED bar with connection indicator dot + Settings gear** |
| **Command Runner** | Buried inside ToolboxScreen behind a "Custom command" dialog modal | **Prominent Quick Command Bar directly at top of Run tab** |
| **Discovered Tools** | Mixed with recipes, services link, schema builder link, and raw submit | **Clean, dedicated Tools section with Scan button & status pills** |
| **Active Jobs** | Mixed in Work tab under status strips and repair cards | **Prominent "Running now" cards with 1-tap Stop button right on the card** |
| **Job Details** | Live output + large hidden details sheet | **Sleek monospace terminal log viewer + 1-tap Copy Output button** |
| **Tool Forms** | Form with Recipe save, Service save, and complex path-picker layer | **Focused parameter form with argv preview and direct Run Tool action** |
| **File Management** | Multi-screen file manager with rename, mkdir, delete, upload, download | **Direct job artifact integration (1-tap Open / Share)** |
| **Settings** | 6-provider AI config matrix, GGUF presets, repair pipelines | **Clean bridge connection info, test connection, stop daemon, re-pair** |
| **Total UI Surfaces** | 14+ distinct visual destinations / layers / dialogs | **6 tightly focused screens (Run, Activity, JobDetail, ToolForm, Connect, Settings)** |
| **Code Footprint** | ~3,600 lines of UI composables | **~1,350 lines of clean, focused composables (-2,250 net lines)** |

---

## 3. Files Changed & Cleaned Up

### Created:
- `android/app/src/main/java/dev/trmx/gui/ui/RunScreen.kt` (Unified Command Runner + Discovered Tools)
- `android/app/src/main/java/dev/trmx/gui/ui/ActivityScreen.kt` (Active jobs with Stop action + Recent jobs history)

### Modified:
- `android/app/src/main/java/dev/trmx/gui/MainActivity.kt` (Streamlined 2-tab navigation, top connection status, settings modal, removed recipe/services/files layers)
- `android/app/src/main/java/dev/trmx/gui/AppViewModel.kt` (Added `runCommandLine` with command token parsing, streamlined `submitToolForm` to auto-open job output)
- `android/app/src/main/java/dev/trmx/gui/ui/SettingsScreen.kt` (Streamlined to connection status, test connection, stop bridge, and re-pair)
- `android/app/src/main/java/dev/trmx/gui/ui/ToolFormScreen.kt` (Removed recipe and service clutter; focused on tool parameter execution)
- `PROGRESS.md` (Updated running audit, keep/cut list, before vs after, verification)

### Deleted (Obsolete Bloat Removed):
- `android/app/src/main/java/dev/trmx/gui/ui/DashboardScreen.kt` (Replaced by `ActivityScreen.kt`)
- `android/app/src/main/java/dev/trmx/gui/ui/ToolboxScreen.kt` (Replaced by `RunScreen.kt`)
- `android/app/src/main/java/dev/trmx/gui/ui/DiagnosticsSheet.kt` (Developer memory/socket diagnostics cut)
- `android/app/src/main/java/dev/trmx/gui/ui/SchemaBuilderScreen.kt` (AI schema generator cut)
- `android/app/src/main/java/dev/trmx/gui/ui/ServicesScreen.kt` (Service daemon supervisor cut)
- `android/app/src/main/java/dev/trmx/gui/ui/SubmitDialog.kt` (Replaced by integrated command runner)
- `android/app/src/main/java/dev/trmx/gui/ui/FilesScreen.kt` (Full file explorer cut; artifacts opened directly)
- `android/app/src/main/java/dev/trmx/gui/ui/AgentScreen.kt` (Agent chat UI retired in favor of Run & Activity)

---

## 4. Verification & Testing

### Sandbox-Side Verifications Run:
1. **Spec Conformance Suite (`python3 tests/spec_conformance.py`)**:
   - Op table checks (`docs/CONTROL-PLANE.md` ↔ `ControlOps.kt`): **100% PASSED**
   - Data-plane route checks (`BridgeClient.kt` ↔ `docs/PROTOCOL.md`): **100% PASSED**
   - Embedded fixture payload checks (`fixtures/v1` ↔ unit test sources): **100% PASSED**
   - AndroidManifest.xml declarations: **100% PASSED**
   - Kotlin block-comment balance check across all source files: **100% PASSED**
   - Fixture payload shapes & Error-code registry: **100% PASSED**
   - Bridge route table & SHA256SUMS: **100% PASSED**
2. **Termux Python Test Suite**:
   - `test_bridge.py`: 41 tests passed (28.7s)
   - `test_tools.py`: 32 tests passed (11.8s)
   - `test_files.py`: 27 tests passed (1.6s)
   - `test_cancel_starting.py`: 2 tests passed (0.05s)
   - `test_retention.py`: 4 tests passed (0.45s)
   - `test_service_status.py`: 12 tests passed (0.24s)
3. **Git Hygiene**:
   - Clean local commits on `agy/trmx-compact`.
   - Never touched `agy/trmx-rebuild` or files outside the repo.
   - Zero git push attempts.

### Honest Local Limits (What Could Not Be Verified Locally):
- **Gradle Android compilation & JVM unit tests**: No JDK or Android SDK is installed in this container environment. Gradle `./gradlew assembleDebug` and `./gradlew test` must run in GitHub Actions CI.
- **Physical device touch interactions**: On-device rendering of Compose components and physical back-button navigation require testing on an actual Android device paired with Termux.

---

## 5. What Was Deliberately Left Alone & Why

1. **Protocol Specifications (`docs/PROTOCOL.md`, `docs/CONTROL-PLANE.md`) & `BridgeClient.kt` API Methods**:
   - Deliberately kept intact to preserve 100% wire protocol compatibility with the Python bridge daemon and existing contract tests.
2. **`TokenStore.kt`, `IntentControlPlane.kt`, and `SetupViewModel.kt`**:
   - Deliberately kept intact because pairing security (encrypted SharedPreferences, loopback tokens, intent handshakes) is rock-solid and necessary for secure communication between Android and Termux.
3. **`FormEngine.kt` and `JobLabels.kt`**:
   - Kept intact and leveraged by `RunScreen` and `ActivityScreen` for clean human labels, duration formatting, and robust input validation.
4. **`FileProvider` & `FilesViewModel.openRemote`**:
   - Kept intact so that job output artifacts (e.g. video files downloaded by `yt-dlp` or logs created by scripts) can be opened in external media players or shared via Android's native share sheet.
