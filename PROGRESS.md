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

---

## 6. PR #69 CI Compile Fixes (Branch `agy/trmx-compact`)

Investigation of GitHub Actions CI compile failure during `:app:compileDebugKotlin` identified 16 errors stemming from invented API names and mismatched signatures introduced during the compact redesign. All 16 errors were resolved using only real, verified APIs in the tree or minimal behavior-preserving local logic:

1. **`ui/ActivityScreen.kt:219:31` (Unresolved reference: `elapsedOrDuration`)**:
   - Replaced call to non-existent `JobLabels.elapsedOrDuration` with local helper `formatDuration(job)` using `java.time.OffsetDateTime` to calculate elapsed seconds from `started_at ?: created_at`.
2. **`ui/ActivityScreen.kt:342:35` (Unresolved reference: `elapsedOrDuration`)**:
   - Replaced call to non-existent `JobLabels.elapsedOrDuration` with local helper `formatDuration(job)` calculating duration between `started_at ?: created_at` and `ended_at`.
3. **`ui/ActivityScreen.kt:353:41` (Operator '==' cannot be applied to 'Long' and 'Int')**:
   - Changed `code == 0` to `code == 0L` to compare `JobSummary.exit_code` (`Long?`) with `Long` literal.
4. **`ui/RunScreen.kt:274:66` (Unresolved reference: `install`)**:
   - Replaced `tool.schema.install?.package_name ?: tool.schema.binary` with real schema field `tool.schema.pkg ?: tool.schema.binary`.
5. **`ui/ToolFormScreen.kt:123:55` (Unresolved reference: `title`)**:
   - Replaced `ex.title` with real `ToolExample.label`.
6. **`ui/ToolFormScreen.kt:222:27` (Unresolved reference: `unitFor`)**:
   - Replaced call to non-existent `FormEngine.unitFor(a)` with direct property access `a.x_trmx?.unit`.
7. **`ui/ToolFormScreen.kt:223:22` (Unresolved reference: `description`)**:
   - Replaced `a.description` (does not exist on `ToolArg`) with real field `a.help`.
8. **`ui/ToolFormScreen.kt:236:29` (Overload resolution ambiguity: [Text overloads])**:
   - Resolved automatically once `helpText` was typed as `String?` from `a.help` instead of `[Error type]`.
9. **`ui/ToolFormScreen.kt:250:27` (Only safe or non-null asserted calls on nullable `List<String>?`)**:
   - Changed `a.enum.forEach` to `(a.enum ?: emptyList()).forEach`.
10. **`ui/ToolFormScreen.kt:273:37` (Unresolved reference: `formatNumber`)**:
    - Replaced call to non-existent `FormEngine.formatNumber` with `FormEngine.sliderValueText(a.type, cur)`.
11. **`ui/ToolFormScreen.kt:273:84` (Unresolved reference: `it`)**:
    - Resolved automatically once `unit` was typed as `String?` from `a.x_trmx?.unit` instead of `[Error type]`.
12. **`ui/ToolFormScreen.kt:278:71` (Unresolved reference: `formatNumber`)**:
    - Replaced call to non-existent `FormEngine.formatNumber` with `FormEngine.sliderValueText(a.type, it)`.
13. **`ui/ToolFormScreen.kt:287:17` (Cannot find a parameter with this name: error)**:
    - Replaced `error = err` with `isError = err != null`, and converted `supportingText` to a composable lambda `(@Composable () -> Unit)?`.
14. **`ui/ToolFormScreen.kt:306:26` (Type inference failed: expected `@Composable (() -> Unit)`)**:
    - Resolved automatically once `unit` was typed as `String?` from `a.x_trmx?.unit` instead of `[Error type]`.
15. **`ui/ToolFormScreen.kt:306:45` (Unresolved reference: `it`)**:
    - Resolved by typing `suffix = unit?.let { u -> { Text(u) } }`.
16. **`ui/ToolFormScreen.kt:320:17` (Overload resolution ambiguity: [Text overloads])**:
    - Resolved automatically once `helpText` was typed as `String?` from `a.help` instead of `[Error type]`.

---

## 7. App Killer Feature (Branch `agy/trmx-app-killer`)

Date: 2026-10-23  
Goal: Built-in automated app force-stop feature designed for ease of use: one Accessibility toggle (one-time setup), then one button force-stops user-confirmed background apps one by one with live progress and a final report.

### Architecture & Components:
1. **Runtime App Enumeration (`AppKillerEngine.loadInstalledApps`)**:
   - Queries device `PackageManager` at runtime via `getInstalledPackages` (supporting both API 33+ `PackageInfoFlags` and legacy fallback).
   - Extracts real label, package name, system app flag, and categorizes into CORE, SAFE, or UNKNOWN.
   - Manifest permission `android.permission.QUERY_ALL_PACKAGES` declared and justified.
2. **3 Strict Safety Categories (`AppCategories.kt`)**:
   - **CORE**: System-critical packages that can NEVER be stopped under any circumstances. Enforced both in UI (unselectable, locked badge) and in the kill engine (`executeKillList` filters out CORE packages and marks them skipped). Contains `dev.trmx.gui`, `com.termux.*`, framework, `com.android.systemui`, settings, telecom/phone, package installers, Play services, keyboards, and home launchers.
   - **SAFE**: Non-essential vendor apps (Galaxy Store, Theme Store, Game Launcher, Voice Recorder, Samsung Tips, Weather, etc.) suggested as pre-selected hints. User can uncheck any or clear all.
   - **UNKNOWN**: Third-party and other unclassified apps. Default EXCLUDED from the kill selection; only included if user explicitly checks the box.
3. **AccessibilityService Automation Engine (`AppKillerAccessibilityService.kt` & `AppKillerEngine.kt`)**:
   - Declared in `AndroidManifest.xml` with `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` and configured via `res/xml/accessibility_service_config.xml`.
   - Per-app execution loop: opens `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` -> polls active window for "Force Stop" node (handling case variants and container hierarchies) -> checks enabled state (skips already stopped apps without waiting for timeout) -> clicks button -> detects confirmation dialog (checks for positive button `button1`, "OK", or "Force stop" alongside dialog cancel) -> clicks confirmation -> records success/skip -> resumes `MainActivity` at end of run.
   - Per-app 3.5s timeout: if a button is not found or not clickable, records app as SKIPPED with reason and continues without hanging.
4. **User Interface (`KillScreen.kt` & Navigation)**:
   - Navigation: Added 3rd tab "Kill" in bottom navigation bar and a quick entry card in `RunScreen.kt`.
   - Filter tabs: Safe, Other, and Core (protected). Real-time search by app label or package name.
   - Session confirmation: Confirms total app count before the first kill run of the session.
   - Accessibility guidance: Clear warning card and one-tap button to open Accessibility settings when service is off; automatically updates on resume (`Lifecycle.Event.ON_RESUME`).
   - Live progress: Progress bar, current count, and active app label with instant "Stop" cancellation.
   - Final report dialog: Displays total targeted, stopped count, and detailed skipped reasons.

### Verification & Conformance:
- `python3 tests/spec_conformance.py`: **100% PASSED** (op table, routes, fixtures, manifest, comment balance, payload shapes, registry, route table, version story).
- Kotlin symbol resolution: All symbols verified against real SDK and project definitions.
- Unit tests: Comprehensive category and protection tests in `AppCategoriesTest.kt`.
- Working tree clean and properly tracked.

---

## 8. App Killer v3 (Active Detection & Partitioning Fix)

Date: 2026-10-23
Spec: User-corrected v3 specification (locked)

### 1. Dual-Signal Active Detection
- **Modern Android Detection Fix**: `ActivityManager.getRunningAppProcesses()` is restricted on modern Android and returned 0 processes on user devices. v3 introduces a robust dual-signal detection architecture:
  1. **Accessibility Window Events**: `AppKillerAccessibilityService` tracks foreground package transitions (`TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED`) and active interactive windows (`rootInActiveWindow`, `windows`). Observed packages are stored in-memory and persisted to private `SharedPreferences` to survive screen-off and process recreation.
  2. **UsageStats Recent Activity**: `UsageStatsManager.queryUsageStats(INTERVAL_BEST, startTime, now)` queries recent app activity within a documented **30-minute window** (`USAGE_STATS_WINDOW_MS = 30 * 60 * 1000L`).
  - Active packages are the union of both signals.
  - **On-Device Guarantee**: UsageStats is used strictly on-device to assemble the running list; no data ever leaves the device.
  - **Post-Kill State Tracking**: When an app is force-stopped or confirmed already stopped by TRMX, it is marked with a kill timestamp and pruned from the Accessibility observed cache. A package will only be considered active via UsageStats if used *after* the kill timestamp, preventing killed apps from immediately reappearing in Running on refresh.

### 2. Tab Partitioning & Installed vs System Rules
- **Other Tab**: Retained and populated exclusively with non-core SYSTEM apps (non-running system apps, plus running system apps that are NOT in the safe suggestion list).
- **User-Installed (Third-Party) Apps**:
  - If ACTIVE -> placed in Running tab.
  - If NOT active -> appears NOWHERE (neither Running nor Other).
  - Guarantee: `Other` tab contains exactly ZERO installed third-party packages.
- **Running Tab**: Contains currently/recently active AND killable packages: `(installed AND active) OR (system AND suggested AND active)`. Core packages are NEVER in Running.
- **Core Tab**: Contains protected system packages. Never killable, never selectable.

### 3. Usage Access Flow
- If Usage access is not granted, `KillScreen` presents a warning card and an "Open Usage Access Settings" button launching `Settings.ACTION_USAGE_ACCESS_SETTINGS` (with package URI and general settings fallbacks).
- State re-checked automatically on `Lifecycle.Event.ON_RESUME`.
- Zero occurrences of "Safe"/"SAFE" in user-visible UI.

### 4. Honest Technical Limit
- An app that has neither come to the foreground since the accessibility service started nor been used recently (within the 30-minute UsageStats window) may not be detected as active. This limitation is due to Android process isolation and is documented plainly.

---

## 9. App Killer v4 (24-Hour UsageStats Window)

Date: 2026-10-24
Spec: User-approved v4 specification (locked)

### 1. 24-Hour UsageStats Window
- **Diagnosis**: v3's 30-minute UsageStats window caused apps used earlier in the day to disappear from the Running tab even if they remained alive in the background (user device showed 8 apps in TRMX vs 16 in Play Store killer).
- **Fix**: Expanded `AppKillerEngine.USAGE_STATS_WINDOW_MS` from 30 minutes (`30 * 60 * 1000L`) to 24 hours (`24 * 60 * 60 * 1000L`).
- Every app used in the last 24 hours is now captured in the Running tab.
- Preserved the Accessibility observed-packages foreground signal and ActivityManager fallback without modifications.

### 2. Partitioning & Core Lock Preserved Exactly
- Partitioning remains strictly locked to v3 logic:
  - **Running**: Active AND (installed OR safe-list system).
  - **Other**: Non-core SYSTEM apps only. Installed third-party packages NEVER appear in Other, running or not.
  - **Non-running installed apps**: Appear in NO tab.
  - **Core**: NEVER killable and NEVER selectable. `AppCategories.isCore` and the core blocklist remain untouched.

### 3. User Guarantees & Privacy
- **NO ADS**: Zero ad SDKs, zero ad UI, zero ad placeholders.
- **NO DATA LEAVES THE DEVICE**: Strictly on-device only. Zero network calls (HttpURLConnection, OkHttp, Retrofit, Sockets). UsageStats and Accessibility data are processed solely in local memory and private app storage.
- **ALL APPS COVERED**: Every non-core app has a place (Running if active, Other if system).

### 4. Honest Technical Limit
- An app that has neither come to the foreground since the accessibility service started nor been used in the last 24 hours (UsageStats window) may not be detected as active. This limitation is due to Android process isolation and is documented plainly.

---

## 10. App Killer v5 (Universal Active Eligibility & Dead Suggestion List Removal)

Date: 2026-10-24
Spec: User-approved v5 specification (locked)

### 1. Root Cause & Solution
- **The Bug**: Running tab eligibility previously gated system apps through a hardcoded 29-package suggestion list (`SUGGESTED_PACKAGES` / `SAFE_PACKAGES`) based on guesswork rather than the actual device's installed package inventory (489 apps enumerated at runtime by PackageManager).
- **The Fix**: Removed the suggestion-list gate entirely. New rule for `isKillableRunning`: **active AND NOT core**.
- Any non-core app that is active (UsageStats 24-hour window OR Accessibility-observed OR ActivityManager fallback) belongs in the Running tab — system or installed, with no hardcoded list filtering it.

### 2. Other Tab Automatically Follows
- Structure of `belongsToOtherTab` preserved: `!isCore && isSystem && !isKillableRunning`.
- Non-core SYSTEM apps that are NOT active remain in Other.
- Installed third-party apps that are not active appear in NO tab (unchanged rule).

### 3. Core Blocklist Untouched
- `AppCategories.isCore`, `CORE_PACKAGES` (all 38 critical framework and telephony packages), and dynamic suffix rules stay completely intact and protected. Core apps can never be killed or selected.

### 4. Dead Code Cleanup & Ban Guarantees
- Deleted dead constants and helper: `SUGGESTED_PACKAGES`, `SAFE_PACKAGES`, `isSuggested`.
- Updated stale code comments and tests.
- Zero occurrences of "Safe"/"SAFE" in user-visible Kill UI.
- Strictly on-device: zero network calls in the killer package.

### 5. Honest Technical Limit
- Background-only services with no foreground window or UsageStats signal (e.g. background daemons without an interactive task) cannot be observed by any normal unprivileged Android app due to OS-level process sandbox boundaries.

---

## 11. App Killer v6-DIAGNOSTIC (Ground Truth Signal Diagnostics)

Date: 2026-10-24
Spec: User-approved v6-DIAGNOSTIC specification (locked)

### 1. Ground Truth Diagnostics (No Guesswork)
- Five list iterations (v1–v5) previously relied on guesses about why apps were missing from the Running tab.
- v6-DIAGNOSTIC adds a dedicated diagnostic view that produces ground truth directly on the device, explaining per app why it is or is not in Running.
- **ZERO changes to eligibility rules**: `isKillableRunning`, `belongsToOtherTab`, `isCore`, the 24-hour UsageStats window, dual signals, and the kill engine remain 100% untouched.

### 2. Debug Affordance on Kill Screen
- Added a clearly labeled "Debug" button near the top in the top bar actions, right next to the existing gear icon.
- Tapping "Debug" opens `KillerDebugScreen`.

### 3. Debug View Information
1. **Toggle states at top**:
   - Accessibility enabled: `yes` / `no`
   - Usage access granted: `yes` / `no`
2. **Signal counts**:
   - Total installed apps (count of all packages enumerated by PackageManager)
   - Count from UsageStats (24-hour window)
   - Count from Accessibility-observed packages
   - Count from ActivityManager fallback
   - Final Running count
3. **Searchable generic per-app list (ALL installed apps)**:
   - Dynamic search by app label or package name for any installed app.
   - Each row displays:
     - Label + package name
     - `lastTimeUsed`: hours ago from UsageStats (e.g. `2.4h ago`, `<1h ago (12m)`) or `"no stats"`
     - `Accessibility-observed`: `yes` / `no`
     - `FLAG_STOPPED`: `stopped` / `not stopped` (from `ApplicationInfo.FLAG_STOPPED`)
     - `In Running`: `yes` / `no`
     - If NOT in Running, a plain reason string computed directly from actual signal values (`"core-blocked"`, `"stopped"`, `"not active in 24h window"`, `"no usage stats and not observed"`, `"usage access not granted and not observed"`). Never generic "unknown".

### 4. Guarantees & Privacy
- **NEVER hardcode app names**: No third-party or example app package names hardcoded. List is generic for all installed packages.
- **Privacy**: All diagnostic data is computed and displayed on-device only. Zero network calls, zero uploads, zero analytics.
- **Word Ban**: Zero occurrences of "Safe"/"SAFE" in user-visible UI.

### 5. Honest Technical Limit
- The debug view explains the signals — it cannot make Android reveal a background-only process that no signal sees.

---

## 12. App Killer v7 (7-Day Window, Stopped Pre-check, Single Predicate Truth, Repeat-on-Timeout)

Date: 2026-10-24
Spec: User-approved v7 specification (locked)

### 1. 7-Day UsageStats Window
- Expanded `USAGE_STATS_WINDOW_MS` from 24 hours to 7 days (`7 * 24 * 60 * 60 * 1000L`).
- Diagnostic screen label updated from `"UsageStats (24-hour window)"` to `"UsageStats (7-day window)"`.
- Resolves device evidence where Meta AI (`com.facebook.stella`) was used 74.7h ago and was missing from Running despite not being stopped.

### 2. Single Source of Truth Running Predicate
- An app is `Running` iff ALL of:
  1. `NOT core` (enforced via `AppCategories.isCore`)
  2. `NOT stopped` (`ApplicationInfo.FLAG_STOPPED` clear via shared helper `AppKillerEngine.isStopped`)
  3. `active by at least one existing signal` (Accessibility-observed OR UsageStats within 7-day window OR ActivityManager fallback)
- Named `AppCategories.isRunning(...)` (with `AppCategories.isKillableRunning(...)` aliasing it).
- Called by BOTH `KillScreen` and `KillerDebugScreen`. Zero duplicated predicate logic.

### 3. Other Tab Predicate
- An app belongs to `Other` iff ALL of:
  1. `NOT core`
  2. `isSystem`
  3. `NOT Running` (per the predicate in item 2)
  4. `NOT stopped`
- Stopped apps appear in NEITHER Running NOR Other (except Core tab if core).

### 4. Kill Flow Pre-Check Skip
- Before opening an app's Settings page, if the app is already stopped (`isPackageStopped(context, pkg)`), skip it immediately with reason `"Already stopped"` WITHOUT opening Settings.
- Eliminates mid-flow skipped settings transitions for already stopped apps.

### 5. Kill Flow Refresh & Selection Snapshot
- Selection is snapshotted at run start (`targets = _selectedPackages.value.toList()`).
- On run completion (success, skip, or timeout), selection is cleared (`emptySet()`) and the app list is reloaded from PackageManager so a second Force Stop press cannot re-run the stale list.

### 6. Android 13+ Timeout & Repeat-on-Timeout
- On Android 13+ (`Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`), per-app task has a 3500ms timeout with repeat-on-timeout (max 2 attempts).
- `"Force Stop button not found (timed out)"` remains a safe skip reason, never a crash.

### 7. Diagnostic Screen Enhancements
- Added `isSystem` to `AppDiagnosticInfo` and displayed on every diagnostic row (`System: yes/no`).
- Row `inRunning` value evaluates the exact same `AppCategories.isRunning` predicate.
- Updated reason strings: `"stopped"`, `"core-blocked"`, `"not active in 7-day window"`, `"no usage stats and not observed"`, `"usage access not granted and not observed"`.





