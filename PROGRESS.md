# TRMX Rebuild Progress

Branch: `agy/trmx-rebuild`
Started: 2026-10-23
Resumed: 2026-10-23

## Audit Plan & Methodology
1. **Repository Structure & Architecture Review**
   - Protocol conformance (`tests/spec_conformance.py`): PASSED 100%
   - Termux Python bridge & helper tests (`termux/tests`): 303 tests ran, 303 PASSED (187.357s)
   - Android Kotlin codebase audit:
     - Networking & SSE stability, reconnection, backoff, resource leaks
     - State management (ViewModels, StateFlow, Coroutines, lifecycle)
     - Security (Cleartext traffic, token handling, intent handling, input validation)
     - Navigation & Broken UX flows
     - Dead code, unused imports, duplicate models, inconsistent contracts
     - Edge cases in parsing, error handling, JSON serialization/deserialization
     - UI / Compose components, OLED theme consistency, error states, empty states
2. **Mistakes & Issues Identified**
   - [x] (Interrupted Run) File picker mode in `FilesScreen` had no Up or Refresh buttons, making file picking navigation trapped.
   - [x] (Interrupted Run) `FilesViewModel.filesUp()` assumed root was always `~` and failed when navigating from `/sdcard` or other absolute paths.
   - [x] (Interrupted Run) Upload cache filename in `FilesViewModel` had a `$${...}` syntax bug generating literal `$` in name.
   - [x] (Interrupted Run) `pendingRecipeId` in `MainActivity` was a plain `var` with `LaunchedEffect(Unit)`, so warm `onNewIntent` shortcuts from home screen never opened the recipe.
   - [x] (Interrupted Run) No way to access Files or Services directly from the top overflow menu.
   - [x] (Interrupted Run) Agent chat screen had no "New chat" action, leaving old completed sessions permanently displayed without a way to reset.
   - [x] (Interrupted Run) Agent session persistence in `AgentViewModel` retained completed/failed tasks indefinitely across app launches.
   - [x] (Interrupted Run) Missing MIME types in `FileMime` for `.epub`, `.html`, `.htm`, `.markdown`.
   - [x] (Interrupted Run) `BridgeClient` file download wrote directly to destination without atomic temp file, leaving corrupt files on abort or network drop.
   - [x] (Interrupted Run) `TokenStore.clear()` used asynchronous `apply()` instead of `commit()`, creating a race condition on logout/unpair.
   - [x] (Interrupted Run) `SetupViewModel` leaked `connectJob` coroutine on ViewModel clear.
   - [x] (Interrupted Run) `AppViewModel.refresh()` did not reset `refreshing = false` on network/http error, hanging the refresh indicator.
   - [x] (Interrupted Run) `AppViewModel` duplicate check ignored `STARTING` status jobs, allowing duplicate job submissions.
   - [x] (Interrupted Run) `AppViewModel` treated 404 from `/v1/services` as an error banner rather than marking feature unsupported.
   - [x] (Interrupted Run) `AppViewModel` didn't update active job detail screen state when job status changed in job list.
   - [x] (Interrupted Run) `termux/tests/test_agent.py` leaked open log file descriptor on `BridgeProc.stop()`.
   - [x] (Resumed Pass) `JobDetailScreen.kt`: `ACTIVE` job state set omitted `"STARTING"`, preventing users from cancelling jobs that were currently in the starting state (inconsistency with `DashboardScreen`).
   - [x] (Resumed Pass) `FormEngine.kt`: Numeric range error formatting used `a.min.toLong()` / `a.max.toLong()`, which truncated fractional float bounds (e.g. `min = 0.5` displayed as `"must be ≥ 0"` instead of `"must be ≥ 0.5"`).
   - [x] (Resumed Pass) `AppViewModel.kt` and `AgentViewModel.kt`: SSE stream reconnection attempt counter (`attempts`) never reset when data frames were successfully received, causing intermittent network drops over long-running sessions to eventually exhaust the 30-attempt budget and abandon the stream.
   - [x] (Resumed Pass) `AgentViewModel.kt`: Explicit user "Stop" action failed to clear `sessionStore` and pending `confirm` state, causing previously stopped tasks to be re-restored on subsequent app process restarts.
   - [x] (Resumed Pass) `AppViewModel.kt`: Cancelling a job from `DashboardScreen` only updated `_detail` (which is null on the dashboard), offering no immediate feedback on the dashboard cards or cancel error reporting.
   - [x] (Resumed Pass) `DashboardScreen.kt`: `DashboardState.notice` was never rendered on screen, making job submission, bridge stopping, and tool availability notices invisible.
   - [x] (Resumed Pass) `FilesScreen.kt`: When opened as a root layer from Work or the overflow menu, `FilesScreen` had no on-screen back/close button, trapping the user unless they repeatedly pressed system back until reaching root `~`.
   - [x] (Resumed Pass) `FilesViewModel.kt`: `openRemote` did not call `sharedDir.mkdirs()` before directory listing, unlike `shareRecipe`.
   - [x] (Resumed Pass) `RecipeStore.kt` & `AgentSessionStore.kt`: Atomic file writes (`tmp.renameTo(file)`) did not have a copy-fallback on failure, risking unhandled exceptions across filesystem/storage boundaries.
3. **Fixes Applied**
   - Fixed all 25 identified issues across Android Kotlin UI, ViewModels, networking, storage, and tests.
   - Committed logically as incremental changes on `agy/trmx-rebuild`.
4. **Verification Status**
   - Python test suite: Ran 303 tests across all `termux/tests/*.py` modules: **303 passed, 0 failures (187.357s)**.
   - Spec conformance suite: `python3 tests/spec_conformance.py`: **100% PASSED**.
   - Kotlin code audit: Complete manual verification of syntax, imports, coroutine scopes, and state flows.
   - Local limits acknowledged: No JDK/Android SDK available on local machine; Gradle builds and JVM unit tests must run in GitHub Actions CI and on-device testing.
