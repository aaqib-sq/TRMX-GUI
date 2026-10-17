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
 *
 * 2026-10-17 (easy-pairing update, after on-device round 1):
 *  - The bridge re-reads its pairing token from bridge.json on every
 *    auth (Config.fresh_token), so the 401 self-heal is now just
 *    re-pair (a file write) + re-probe — no STOP/START, no dependence
 *    on two more intents landing.
 *  - Every probe / op / stage is written to a diagnostics log
 *    (filesDir/setup_log.txt, see SetupLog) that the setup screen can
 *    show and share — device evidence instead of theories.
 *  - Probe HTTP timeouts are 3 s (the OkHttp default 10 s let a single
 *    frozen-bridge probe eat the whole wait and made buttons feel dead).
 *  - The skip tap now fires WizardEvent.SkipWait immediately, so the
 *    screen responds the moment it is tapped.
 *  - Pipeline progress is persisted; if the process dies mid-setup
 *    (Samsung kills the app on switch — round 1), returning shows an
 *    honest "setup was interrupted" card whose retry resumes the
 *    pipeline instead of the section silently vanishing.
 */

import android.app.Application
import android.content.Context
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
import dev.trmx.gui.net.SetupLog
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
import java.io.File
import java.util.concurrent.TimeUnit

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

    // Setup probes get SHORT timeouts: loopback either answers in
    // milliseconds or refuses instantly. The default OkHttp 10 s timeouts
    // let one probe against a frozen bridge process burn the whole wait
    // (round 1: the handshake stretched past 10 minutes and every button
    // felt dead while a probe was in flight).
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    // Diagnostics (2026-10-17): every probe/op/stage lands in this log;
    // the setup screen shows it under "विवरण / लॉग" and can share it.
    private val setupLog = SetupLog(File(app.filesDir, "setup_log.txt"))
    private val _setupLogText = MutableStateFlow("")
    val setupLogText = _setupLogText.asStateFlow()

    private fun slog(event: String) {
        setupLog.log(event)
        _setupLogText.value = setupLog.read()
    }

    /** Pipeline stages; the setup screen shows while this is not DONE. */
    private val _wizard = MutableStateFlow(WizardState())
    val wizard = _wizard.asStateFlow()

    private val _checks = MutableStateFlow(SetupChecks())
    val checks = _checks.asStateFlow()

    private val _repair = MutableStateFlow(RepairState())
    val repair = _repair.asStateFlow()

    private var sequenceJob: Job? = null
    private val skipSignal = Channel<Unit>(Channel.CONFLATED)

    // Mid-pipeline progress is persisted so a killed process does not
    // evaporate the setup (round 1: leaving via Recents made the whole
    // pipeline section vanish). Only the STEP is stored — the token
    // lives in TokenStore, details are re-derived on resume.
    private val progressPrefs =
        app.getSharedPreferences("trmx_setup_progress", Context.MODE_PRIVATE)

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

    init {
        _setupLogText.value = setupLog.read()
        recheck()
        val restored = restoreProgress()
        if (!restored && store.isPaired) warmReconnect()
        viewModelScope.launch { _wizard.collect { persistProgress(it) } }
    }

    private fun persistProgress(s: WizardState) {
        if (s.step != WizardStep.WELCOME && s.step != WizardStep.DONE) {
            progressPrefs.edit().putString("step", s.step.name).apply()
        } else {
            progressPrefs.edit().remove("step").apply()
        }
    }

    /** Returns true when an interrupted pipeline was restored (the
     *  caller then skips the warm reconnect — the retry button on the
     *  restored card resumes the pipeline from the persisted step). */
    private fun restoreProgress(): Boolean {
        val name = progressPrefs.getString("step", null) ?: return false
        val step = runCatching { WizardStep.valueOf(name) }.getOrNull() ?: return false
        if (step == WizardStep.WELCOME || step == WizardStep.DONE) return false
        if (!store.isPaired) return false
        _wizard.value = WizardState(
            step = step,
            baseUrl = store.installBase,
            token = store.token,
            consentTermux = true,
            consentRunCommand = true,
            consentAllowExternal = true,
            busy = false,
            failure = "सेटअप बीच में रुक गया था — ऐप बंद हो गया था।\n" +
                "नीचे 'फिर से कोशिश करो' दबाओ — सेटअप वहीं से जारी होगा।")
        slog("RESTORE interrupted setup at step $step")
        return true
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
        slog("=== BEGIN setup (${SetupLog.describeToken(store.token)}) ===")
        sequenceJob?.cancel()
        skipSignal.tryReceive()
        sequenceJob = viewModelScope.launch { runPipeline() }
    }

    /** Accelerator for the python wait — the pipeline advances on its own
     *  when the wait expires; nobody has to tap this. The SkipWait event
     *  fires FIRST so the screen changes the moment it is tapped (round 1:
     *  the tap used to change nothing on screen until an in-flight probe
     *  returned — the button looked dead). */
    fun skipPythonWait() {
        applyEvent(WizardEvent.SkipWait)
        skipSignal.trySend(Unit)
        slog("SKIP python wait (user tap)")
    }

    /** The persisted pairing token ("" when never paired). Exposed so the
     *  setup screen can offer manual pairing at any time — the user must
     *  never have to wait for a pipeline failure to reach it (device
     *  round 1: a slow handshake hid the failure card, and with it the
     *  manual escape hatch, for 10+ minutes). */
    val manualToken: String get() = store.token

    /**
     * After a manual pair in Termux: cancel whatever the pipeline is
     * doing and check the data plane once with the persisted token.
     * Success finishes setup; a 401 explains itself; silence gets a
     * short, honest retry hint (the bridge may still be starting).
     * Since 2026-10-17 the manual path needs NO stop/start around the
     * pair — a running bridge re-reads the token file on the next auth.
     */
    fun verifyManualPairing() {
        slog("=== MANUAL verify (${SetupLog.describeToken(store.token)}) ===")
        sequenceJob?.cancel()
        sequenceJob = viewModelScope.launch {
            when (val r = probe()) {
                is BridgeResult.Success ->
                    applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
                is BridgeResult.HttpError -> failHandshake(r)
                is BridgeResult.NetworkError ->
                    applyEvent(WizardEvent.StepFailed(
                        "ब्रिज ने अभी जवाब नहीं दिया — 'trmx pair' चलाने के बाद " +
                            "कुछ सेकंड रुककर फिर 'चेक करो' दबाओ।\n" +
                            "आखिरी एरर: ${r.cause.message}"))
            }
        }
    }

    fun retrySetup() {
        slog("=== RETRY from ${_wizard.value.step} ===")
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
     * stage — the screen folds the pipeline into four honest stages.
     */
    private suspend fun runPipeline(fromStep: WizardStep = WizardStep.INSTALL_PY) {
        slog("PIPELINE from $fromStep")
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
            // The skip is checked BEFORE each probe, so a tap is honored
            // within one short-timeout probe at the worst — and the
            // screen itself already changed on the tap (SkipWait event).
            if (skipSignal.tryReceive().isSuccess) return false
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
        slog("=== WARM reconnect (${SetupLog.describeToken(store.token)}) ===")
        sequenceJob = viewModelScope.launch {
            applyEvent(WizardEvent.StepStarted("ब्रिज से दोबारा कनेक्ट हो रहा है…"))
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
            is BridgeResult.Success -> {
                slog("HANDSHAKE ok — setup DONE")
                applyEvent(WizardEvent.StepSucceeded(WizardStep.DONE))
            }
            is BridgeResult.HttpError -> {
                // 401 with a reachable bridge: our token is not the bridge's
                // token. Self-heal once: re-pair, then re-check.
                // Since 2026-10-17 the bridge re-reads the token from
                // bridge.json on every auth, so re-pairing is only a file
                // write — a live bridge accepts it on the next probe. The
                // heal deliberately does NOT stop/start the bridge: that
                // dependency on two more intents landing is what made the
                // old heal fail all night in round 1.
                if (r.status == 401 && allowRecovery) {
                    slog("HEAL 401 — re-pair (file write), then re-probe")
                    applyEvent(WizardEvent.StepStarted(
                        "टोकन मेल नहीं खाया — दोबारा पेयर करके चेक कर रहे हैं…"))
                    if (!sendOp(ControlOps.spec("PAIR")!!, token = store.token)) return
                    delay(600L)
                    awaitHandshakeOrFail(allowRecovery = false)
                } else {
                    failHandshake(r)
                }
            }
            is BridgeResult.NetworkError ->
                applyEvent(
                    WizardEvent.StepFailed(
                        "कनेक्ट नहीं हुआ — ब्रिज ने जवाब ही नहीं दिया।\n" +
                            "आम वजहें:\n" +
                            "• allow-external-apps सेट नहीं है (~/.termux/termux.properties में)\n" +
                            "• इंस्टॉल अधूरा रह गया — Termux में ~/.trmx/trmx status चलाकर देखो\n" +
                            "• Android ने Termux को बंद कर दिया (phantom process limit)\n" +
                            "आखिरी एरर: ${r.cause.message}"))
        }
    }

    private fun failHandshake(r: BridgeResult.HttpError) {
        val hint = if (r.status == 401) {
            "कनेक्ट नहीं हुआ — ब्रिज ने टोकन मना कर दिया (HTTP 401)।\n" +
                "चेक करो:\n" +
                "• TRMX को Termux:Run Command परमिशन मिली है (App info → Permissions)\n" +
                "• ~/.termux/termux.properties में allow-external-apps=true है " +
                "(फिर termux-reload-settings चलाकर Termux रीस्टार्ट करो)\n" +
                "• नीचे 'विवरण / लॉग' खोलकर लॉग भेजो — उसमें साफ़ दिखेगा ऐप ने क्या भेजा था"
        } else {
            "कनेक्ट नहीं हुआ — ब्रिज ने HTTP ${r.status} (${r.code}) जवाब दिया।"
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
            // PAIR's token: the explicit arg, else the wizard state's,
            // else the persisted store token. The wizard state only
            // holds a token during a first-run Begin — the warm-reconnect
            // 401 self-heal reaches PAIR with an empty wizard token, and
            // resolving "<TOKEN>" with "" crashed the app (device crash
            // report, 2026-10-16 22:23).
            token = if (spec.id == "PAIR") {
                token ?: s.token.ifEmpty { store.token }
            } else {
                null
            })
        return res.fold(
            onSuccess = {
                slog("OP ${spec.id} -> sent")
                true
            },
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
                slog("OP ${spec.id} -> FAILED: $reason")
                onFailure(reason)
                false
            })
    }

    /** One data-plane probe, timed and written to the diagnostics log —
     *  this is the evidence line a shared log is judged by: what was
     *  asked, what came back, how long it took, and WHICH token family
     *  was sent (length + prefix only, never the token itself). */
    private suspend fun probe(): BridgeResult<SystemInfo> {
        val started = System.currentTimeMillis()
        val r = client().systemInfo()
        val ms = System.currentTimeMillis() - started
        val tok = SetupLog.describeToken(store.token)
        when (r) {
            is BridgeResult.Success ->
                slog("PROBE /v1/system/info -> 200 (${ms}ms) $tok")
            is BridgeResult.HttpError ->
                slog("PROBE /v1/system/info -> HTTP ${r.status} ${r.code} (${ms}ms) $tok")
            is BridgeResult.NetworkError ->
                slog("PROBE /v1/system/info -> NETWORK " +
                    "${r.cause.javaClass.simpleName}: ${r.cause.message} (${ms}ms) $tok")
        }
        return r
    }

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
