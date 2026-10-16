package dev.trmx.gui.wizard

/*
 * Setup's viewmodel: the one-screen setup + one-tap repair.
 *
 * The working intent-op machinery (INSTALL_PY / INSTALL / STOP / PAIR /
 * START / HANDSHAKE, the 401 self-heal, the handshake poller) is kept as it
 * was — re-sequenced into a single background pipeline with one honest
 * progress bar, not rewritten. The old 7-step wizard's self-declared
 * checkboxes are replaced by a self-checking permission checklist: two
 * items are verified live (Termux installed? RUN_COMMAND granted?) and
 * re-verified on resume; allow-external-apps is confirmed by the user
 * (Termux's own security boundary — no app can flip it) and proven
 * indirectly when the handshake succeeds.
 *
 * NOTE on `trmx doctor`: PAIRING-PLAN wants the preflight + python
 * auto-skip to come from `trmx doctor`, but that command does not exist
 * bridge-side yet — intents are fire-and-forget, so the app cannot read a
 * doctor report through them. Until the bridge speaks it, the pipeline
 * does a data-plane preflight (bridge already up → skip everything) and
 * the python wait auto-advances with a manual skip as an accelerator.
 */

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.control.ControlOps
import dev.trmx.gui.control.ControlPlaneException
import dev.trmx.gui.control.IntentControlPlane
import dev.trmx.gui.model.SystemInfo
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.Handshaker
import dev.trmx.gui.store.TokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** The self-checking permission checklist. */
data class SetupChecks(
    val termuxInstalled: Boolean = false,
    val runCommandGranted: Boolean = false,
    val allowExternalConfirmed: Boolean = false,
    /** Which checklist item the user is currently fixing in settings. */
    val resolving: String? = null,
) {
    val ready: Boolean
        get() = termuxInstalled && runCommandGranted && allowExternalConfirmed
}

/** One-tap "Repair connection" state (Settings). Independent of the
 *  first-run gate — repair never yanks the user back to the setup screen. */
data class RepairState(
    val running: Boolean = false,
    val label: String = "",
    val failure: String? = null,
    val finished: Boolean = false,
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app
    private val store = TokenStore(app)
    private val controlPlane = IntentControlPlane(app)
    private val wizardEngine = WizardEngine()
    private val http = OkHttpClient()

    /** Pipeline stages; the setup screen shows while this is not DONE. */
    private val _wizard = MutableStateFlow(WizardState())
    val wizard = _wizard.asStateFlow()

    private val _checks = MutableStateFlow(SetupChecks())
    val checks = _checks.asStateFlow()

    private val _repair = MutableStateFlow(RepairState())
    val repair = _repair.asStateFlow()

    private var sequenceJob: Job? = null
    private val skipSignal = Channel<Unit>(Channel.CONFLATED)

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

    init {
        recheck()
        if (store.isPaired) warmReconnect()
    }

    // ---- checklist ------------------------------------------------------

    /**
     * Re-verify what the app can verify itself. Called on init and on every
     * resume — the screen's "waiting for you" states resolve here when the
     * user comes back from settings. Verified facts feed the WizardEngine's
     * consent gate (a checked box and a verified fact both mean "done").
     */
    fun recheck() {
        val termux = controlPlane.isTermuxInstalled()
        val runCmd = controlPlane.isRunCommandPermissionGranted()
        _checks.update {
            it.copy(termuxInstalled = termux, runCommandGranted = runCmd,
                    resolving = null)
        }
        applyEvent(WizardEvent.ConsentTermux(termux))
        applyEvent(WizardEvent.ConsentRunCommand(runCmd))
    }

    /** Deep-link: TRMX's own App info — RUN_COMMAND hides under
     *  Permissions → Additional permissions. This is the ceiling Android
     *  allows; there is no normal permission dialog for it. */
    fun openRunCommandSettings() {
        _checks.update { it.copy(resolving = "runcommand") }
        runCatching {
            appContext.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                       Uri.fromParts("package", appContext.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Deep-link: open the Termux app itself so the user can add
     *  `allow-external-apps=true` to ~/.termux/termux.properties. */
    fun openTermuxApp() {
        _checks.update { it.copy(resolving = "allowexternal") }
        runCatching {
            appContext.packageManager
                .getLaunchIntentForPackage(ControlOps.TERMUX_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { appContext.startActivity(it) }
        }
    }

    /** The user confirms they flipped the Termux toggle (the app cannot
     *  verify this one directly — a working handshake proves it later). */
    fun confirmAllowExternal() {
        _checks.update { it.copy(allowExternalConfirmed = true, resolving = null) }
        applyEvent(WizardEvent.ConsentAllowExternal(true))
    }

    // ---- first-run pipeline -----------------------------------------------

    fun beginSetup() {
        applyEvent(WizardEvent.Begin(
            if (store.isPaired) store.token
            else store.generateToken().also { store.token = it }))
        if (!_wizard.value.allConsentsGiven) return   // failure is on the state
        store.installBase = _wizard.value.baseUrl
        sequenceJob?.cancel()
        skipSignal.tryReceive()
        sequenceJob = viewModelScope.launch { runPipeline() }
    }

    /** Accelerator for the python wait — the pipeline advances on its own
     *  when the wait expires; nobody has to tap this. */
    fun skipPythonWait() {
        skipSignal.trySend(Unit)
    }

    fun retrySetup() {
        applyEvent(WizardEvent.Retry)
        sequenceJob?.cancel()
        skipSignal.tryReceive()
        // Device round 1 lesson (2026-10-16): if the handshake failed,
        // re-polling a dead port can never heal it — the bridge may
        // simply not be running (stopped, killed, or never started
        // because the earlier steps ran against a half-installed
        // Termux). Resume from the PAIR block instead: it re-runs
        // STOP → PAIR → START before the next handshake, which heals
        // both a dead bridge and a stale in-memory token.
        val failedAt = _wizard.value.step
        val resume = if (failedAt == WizardStep.HANDSHAKE) WizardStep.PAIR else failedAt
        sequenceJob = viewModelScope.launch { runPipeline(fromStep = resume) }
    }

    fun resetSetup() {
        sequenceJob?.cancel()
        skipSignal.tryReceive()
        applyEvent(WizardEvent.Reset)
        recheck()
    }

    /**
     * The working first-run sequence, re-sequenced into one pipeline:
     * INSTALL_PY → INSTALL → STOP → PAIR → START → HANDSHAKE. Each op's
     * acceptance is observed on the data plane, never assumed from intent
     * delivery (CONTROL-PLANE.md §1). STOP is silent inside the pairing
     * stage — the screen counts five honest stages.
     */
    private suspend fun runPipeline(fromStep: WizardStep = WizardStep.INSTALL_PY) {
        var step = fromStep
        if (step == WizardStep.WELCOME) step = WizardStep.INSTALL_PY
        if (step == WizardStep.INSTALL_PY) {
            // Preflight: if the bridge already answers with our token,
            // everything below is unnecessary — skip straight to done.
            when (probe()) {
                is BridgeResult.Success -> {
                    applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
                    return
                }
                else -> {}
            }
        }
        if (step.ordinal >= WizardStep.INSTALL_PY.ordinal &&
            step.ordinal < WizardStep.INSTALL.ordinal
        ) {
            if (!sendOp(ControlOps.spec("INSTALL_PY")!!)) return
            // Observed wait (device round 1, 2026-10-16): the old code
            // burned the full 40 s blind window even when Python and the
            // bridge were already in place, and the skip tap alone could
            // not move a pipeline whose later steps were not ready. Poll
            // the data plane while waiting: any HTTP answer proves the
            // bridge binaries — and therefore Python — already exist; a
            // Success means the bridge already trusts our token and the
            // whole setup is effectively done.
            if (awaitInstallPy()) {
                applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
                return
            }
            applyEvent(WizardEvent.StepSucceeded(WizardStep.INSTALL))
        }
        if (step.ordinal < WizardStep.PAIR.ordinal) {
            if (!sendOp(ControlOps.spec("INSTALL")!!)) return
            awaitAnyAnswer(INSTALL_WAIT_MS)
            applyEvent(WizardEvent.StepSucceeded(WizardStep.PAIR))
        }
        if (step.ordinal < WizardStep.START.ordinal) {
            // STOP first, always: a bridge left running (e.g. started manually
            // in Termux) holds its OLD token in memory and would 401 the app
            // even after a successful pair. Stopping is idempotent — a no-op
            // when nothing is running.
            if (!sendOp(ControlOps.spec("STOP")!!)) return
            delay(1_000L)
            if (!sendOp(ControlOps.spec("PAIR")!!)) return
            delay(PAIR_WAIT_MS)
            applyEvent(WizardEvent.StepSucceeded(WizardStep.START))
        }
        if (step.ordinal < WizardStep.HANDSHAKE.ordinal) {
            if (!sendOp(ControlOps.spec("START")!!)) return
            applyEvent(WizardEvent.StepSucceeded(WizardStep.HANDSHAKE))
        }
        awaitHandshakeOrFail()
    }

    /**
     * The INSTALL_PY stage wait, observed instead of blind: polls the
     * bridge every [POLL_WAIT_MS] until [INSTALL_PY_WAIT_MS] elapses.
     * Returns true only when the bridge already answers WITH our token
     * (setup effectively complete). Any other HTTP answer (e.g. a 401
     * from a bridge holding an older token) ends the wait early — the
     * remaining stages will re-pair. The user's skip tap also ends it.
     */
    private suspend fun awaitInstallPy(): Boolean {
        val deadline = System.currentTimeMillis() + INSTALL_PY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            when (probe()) {
                is BridgeResult.Success -> return true
                is BridgeResult.HttpError -> return false
                is BridgeResult.NetworkError -> {}
            }
            if (withTimeoutOrNull(POLL_WAIT_MS) { skipSignal.receive() } != null) {
                return false
            }
        }
        return false
    }

    /** Waits up to [timeoutMs], returning as soon as the bridge answers
     *  anything at all (proof the install landed and it is up). */
    private suspend fun awaitAnyAnswer(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (probe()) {
                is BridgeResult.Success, is BridgeResult.HttpError -> return
                is BridgeResult.NetworkError -> {}
            }
            delay(POLL_WAIT_MS)
        }
    }

    /** Warm start (was AppViewModel.warmStart): probe, START if down, probe
     *  again. Runs automatically when a pairing token is already saved. */
    private fun warmReconnect() {
        sequenceJob = viewModelScope.launch {
            applyEvent(WizardEvent.StepStarted("reconnecting to the bridge…"))
            when (probe()) {
                is BridgeResult.Success ->
                    applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
                is BridgeResult.HttpError ->
                    awaitHandshakeOrFail()
                is BridgeResult.NetworkError -> {
                    if (!sendOp(ControlOps.spec("START")!!)) return@launch
                    applyEvent(WizardEvent.StepSucceeded(WizardStep.HANDSHAKE))
                    awaitHandshakeOrFail()
                }
            }
        }
    }

    // ---- one-tap repair (Settings) ----------------------------------------

    fun runRepair() {
        if (_repair.value.running) return
        _repair.value = RepairState(running = true, label = "Repairing the connection…")
        viewModelScope.launch {
            val token = store.token
            if (token.length < 20) {
                _repair.value = RepairState(
                    failure = "No pairing token saved — run setup again.")
                return@launch
            }
            _repair.update { it.copy(label = "Stopping the old bridge…") }
            if (!sendOp(ControlOps.spec("STOP")!!,
                        onFailure = { failRepair(it) })) return@launch
            delay(1_000L)
            _repair.update { it.copy(label = "Pairing…") }
            if (!sendOp(ControlOps.spec("PAIR")!!, token = token,
                        onFailure = { failRepair(it) })) return@launch
            // No long blind wait here: START + the handshake poll below
            // (500ms × 60) detect readiness honestly.
            delay(1_000L)
            _repair.update { it.copy(label = "Starting the bridge…") }
            if (!sendOp(ControlOps.spec("START")!!,
                        onFailure = { failRepair(it) })) return@launch
            _repair.update { it.copy(label = "Waiting for the bridge…") }
            when (val r = handshaker().awaitHandshake()) {
                is BridgeResult.Success ->
                    _repair.value = RepairState(finished = true)
                is BridgeResult.HttpError ->
                    _repair.value = RepairState(
                        failure = "The bridge answered with HTTP ${r.status} " +
                            "(${r.code}). If it keeps failing, re-run setup.")
                is BridgeResult.NetworkError ->
                    _repair.value = RepairState(
                        failure = "The bridge did not answer. Make sure Termux " +
                            "is installed and running, allow-external-apps is " +
                            "set, then try again.")
            }
        }
    }

    private fun failRepair(reason: String) {
        _repair.value = RepairState(failure = reason)
    }

    fun dismissRepair() {
        _repair.value = RepairState()
    }

    // ---- plumbing (unchanged semantics) -------------------------------------

    private suspend fun awaitHandshakeOrFail(allowRecovery: Boolean = true) {
        when (val r = handshaker().awaitHandshake()) {
            is BridgeResult.Success ->
                applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
            is BridgeResult.HttpError -> {
                // 401 with a reachable bridge: our token is not the bridge's
                // token. Self-heal once: re-pair, restart, retry.
                if (r.status == 401 && allowRecovery) {
                    applyEvent(WizardEvent.StepStarted(
                        "token rejected — re-pairing and restarting the bridge…"))
                    if (!sendOp(ControlOps.spec("PAIR")!!)) return
                    delay(2_000L)
                    if (!sendOp(ControlOps.spec("STOP")!!)) return
                    delay(2_000L)
                    if (!sendOp(ControlOps.spec("START")!!)) return
                    awaitHandshakeOrFail(allowRecovery = false)
                } else {
                    failHandshake(r)
                }
            }
            is BridgeResult.NetworkError ->
                applyEvent(
                    WizardEvent.StepFailed(
                        "The bridge did not answer. Most likely causes:\n" +
                            "• allow-external-apps is not set in ~/.termux/termux.properties\n" +
                            "• the install failed — open Termux and run ~/.trmx/trmx status\n" +
                            "• Termux was killed by Android (phantom process limits)\n" +
                            "Last network error: ${r.cause.message}"))
        }
    }

    private fun failHandshake(r: BridgeResult.HttpError) {
        val hint = if (r.status == 401) {
            "The bridge rejected our token (HTTP 401) — pairing did not take effect.\n" +
                "Checklist:\n" +
                "• TRMX holds the Termux:Run Command permission (App info → Permissions)\n" +
                "• ~/.termux/termux.properties contains allow-external-apps=true\n" +
                "  (then run termux-reload-settings and restart Termux)\n" +
                "• no other bridge instance is running with an old token"
        } else {
            "The bridge answered with HTTP ${r.status} (${r.code})."
        }
        applyEvent(WizardEvent.StepFailed(hint))
    }

    /** Sends one op; on send failure reports via [onFailure] and returns false. */
    private fun sendOp(
        spec: ControlOps.OpSpec,
        baseUrl: String? = null,
        token: String? = null,
        onFailure: (String) -> Unit = { reason ->
            applyEvent(WizardEvent.StepFailed(reason))
        },
    ): Boolean {
        val s = _wizard.value
        val res = controlPlane.send(
            spec,
            baseUrl = if (spec.id == "INSTALL") s.baseUrl else null,
            token = if (spec.id == "PAIR") (token ?: s.token) else null)
        return res.fold(
            onSuccess = { true },
            onFailure = { e ->
                val reason = if (e is ControlPlaneException) {
                    when (e.error) {
                        ControlPlaneException.SendError.PERMISSION_REQUIRED ->
                            "RUN_COMMAND permission is not granted to TRMX. " +
                                "Open App info → Permissions and grant it."
                        ControlPlaneException.SendError.TERMUX_NOT_RUNNING ->
                            "Termux did not accept the command. Open Termux once, then retry."
                    }
                } else {
                    "Could not send ${spec.id}: ${e.message}"
                }
                onFailure(reason)
                false
            })
    }

    private suspend fun probe(): BridgeResult<SystemInfo> = client().systemInfo()

    private fun handshaker() =
        Handshaker(probe = { probe() }, pollIntervalMs = 500, maxAttempts = 60)

    private fun applyEvent(e: WizardEvent) = _wizard.update { wizardEngine.reduce(it, e) }

    override fun onCleared() {
        sequenceJob?.cancel()
        super.onCleared()
    }

    companion object {
        // Fixed waits are INTENT-EXECUTION padding only: Termux executes a
        // RUN_COMMAND in ~0.5–2s when alive. Longer blind waits are pure
        // waste — a slow/failed step is caught by the handshake afterwards
        // with a guiding error and retry. (Self-audit 2026-10-16: INSTALL
        // 20s→10s, PAIR 3s→1.5s, repair post-PAIR 2s→1s.)
        private const val INSTALL_PY_WAIT_MS = 40_000L
        private const val INSTALL_WAIT_MS = 10_000L
        private const val PAIR_WAIT_MS = 1_500L
        private const val POLL_WAIT_MS = 2_000L
    }
}
