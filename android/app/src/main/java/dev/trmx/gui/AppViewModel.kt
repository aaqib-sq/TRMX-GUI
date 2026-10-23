package dev.trmx.gui

/*
 * AppViewModel — orchestrates the data plane (BridgeClient) and exposes UI
 * state for the tab screens. First-run setup / repair lives in
 * SetupViewModel; the agent chat in AgentViewModel; file browsing in
 * FilesViewModel; the AI backend config in AiConfigViewModel. Kept deliberately
 * thin: all decision logic lives in pure, tested helpers (OutputReducer,
 * SubmitValidator, FormEngine, …).
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.control.ControlOps
import dev.trmx.gui.control.IntentControlPlane
import dev.trmx.gui.job.JobOutputState
import dev.trmx.gui.model.FileEntry
import dev.trmx.gui.job.OutputReducer
import dev.trmx.gui.job.SubmitValidator
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.model.ServiceDefRequest
import dev.trmx.gui.model.ServiceStatus
import dev.trmx.gui.model.SubmitRequest
import dev.trmx.gui.model.SystemInfo
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.SseBackoff
import dev.trmx.gui.net.SseClient
import dev.trmx.gui.net.SseFrame
import dev.trmx.gui.store.TokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import android.net.Uri
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.model.ToolSubmitRequest
import dev.trmx.gui.store.Recipe
import dev.trmx.gui.store.RecipeStore
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.tools.Artifact
import dev.trmx.gui.tools.Artifacts
import dev.trmx.gui.tools.FormEngine
import dev.trmx.gui.tools.ToolJobMeta
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

data class DashboardState(
    val info: SystemInfo? = null,
    val jobs: List<JobSummary> = emptyList(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

data class SubmitFormState(
    val name: String = "",
    val argvText: String = "",
    val cwd: String = "",
    val timeoutText: String = "",
    val submitting: Boolean = false,
    val errors: List<SubmitValidator.FieldError> = emptyList(),
)


/** Toolbox state (PROTOCOL §7). */
data class ToolsState(
    val tools: List<ToolStatus> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val schemaErrors: List<String> = emptyList(),
)

/** AI Schema Builder state (AI round, ADR-014). */
data class SchemaBuilderState(
    val description: String = "",
    val submitting: Boolean = false,
    val jobId: String? = null,      // running/finished trmx-ai job
    val result: String? = null,     // terminal notice (success or failure)
    val error: String? = null,      // submit-side error (validation/HTTP)
)


/** Services state (protocol 1.1, §14). */
data class ServicesState(
    val services: List<ServiceStatus> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** null = unknown (bridge < 0.5.0); false shows the upgrade card */
    val supported: Boolean? = null,
)

/** One dynamic tool form (a schema + live field values). */
data class ToolFormState(
    val schema: dev.trmx.gui.model.ToolSchema? = null,
    val values: Map<String, FieldValue> = emptyMap(),
    val touched: Set<String> = emptySet(),   // errors surface only after touch (Ph 9.5)
    val submitting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val pathArg: String? = null,        // arg being picked in the Files browser
)

/** Artifacts of the opened (completed) job — Phase 9.5. */
data class ArtifactsState(
    val jobId: String? = null,
    val artifacts: List<Artifact> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class JobDetailState(
    val jobId: String,
    val job: JobSummary? = null,
    val error: String? = null,
    val cancelling: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TokenStore(app)
    private val controlPlane = IntentControlPlane(app)
    private val http = OkHttpClient()

    private val _dashboard = MutableStateFlow(DashboardState())
    val dashboard = _dashboard.asStateFlow()

    private val _submitForm = MutableStateFlow(SubmitFormState())
    val submitForm = _submitForm.asStateFlow()

    private val _detail = MutableStateFlow<JobDetailState?>(null)
    val detail = _detail.asStateFlow()

    private val _output = MutableStateFlow<JobOutputState?>(null)
    val output = _output.asStateFlow()


    private val recipeStore = RecipeStore(getApplication<Application>().filesDir)

    private val _tools = MutableStateFlow(ToolsState())
    val tools = _tools.asStateFlow()

    private val _toolForm = MutableStateFlow(ToolFormState())
    val toolForm = _toolForm.asStateFlow()

    private val _schemaBuilder = MutableStateFlow(SchemaBuilderState())
    val schemaBuilder = _schemaBuilder.asStateFlow()


    private val _services = MutableStateFlow(ServicesState())
    val services = _services.asStateFlow()

    private val _recipes = MutableStateFlow<List<Recipe>>(emptyList())
    val recipes = _recipes.asStateFlow()

    init {
        _recipes.value = recipeStore.list()
        // Chains were removed (ADR-010 amendment, 2026-10-20 audit §6-H1);
        // drop any chains.json an older build left behind.
        runCatching { File(getApplication<Application>().filesDir,
                           "chains.json").delete() }
        refreshShortcuts()
    }

    private val _artifacts = MutableStateFlow(ArtifactsState())
    val artifacts = _artifacts.asStateFlow()

    /** tool jobs submitted this session: jobId -> outputs/outdir (Ph 9.5) */
    private val toolJobMeta = HashMap<String, ToolJobMeta>()

    private var installWatch: String? = null   // job_id of a running pkg install
    private var aiWatch: String? = null        // job_id of a running trmx-ai job

    private var pollJob: Job? = null
    private var outputJob: Job? = null
    private var eventsJob: Job? = null
    private fun sseClient() = SseClient(store.bridgeBaseUrl, store.token, http)

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

    /**
     * Full local reset for "Re-run setup": clears every cached screen state.
     * The setup flow itself is owned by SetupViewModel (resetSetup) — call
     * both together.
     */
    fun resetAppState() {
        pollJob?.cancel()
        outputJob?.cancel()
        eventsJob?.cancel()
        _dashboard.value = DashboardState()
        _output.value = null
        _toolForm.value = ToolFormState()
        _recipes.value = recipeStore.list()
        _artifacts.value = ArtifactsState()
    }

    // ---- submit form ----------------------------------------------------

    fun editName(v: String) = _submitForm.update { it.copy(name = v) }
    fun editArgvText(v: String) = _submitForm.update { it.copy(argvText = v) }
    fun editCwd(v: String) = _submitForm.update { it.copy(cwd = v) }
    fun editTimeout(v: String) = _submitForm.update { it.copy(timeoutText = v) }

    fun clearSubmitErrors() = _submitForm.update { it.copy(errors = emptyList()) }

    fun submitJob() {
        val form = _submitForm.value
        val argv = SubmitValidator.parseArgvText(form.argvText)
        val timeout = form.timeoutText.trim().toLongOrNull()
        if (timeout == null && form.timeoutText.isNotBlank()) {
            _submitForm.update {
                it.copy(errors = listOf(SubmitValidator.FieldError("timeout_s", "Timeout must be a number of seconds (or empty).")))
            }
            return
        }
        val errors = SubmitValidator.validate(form.name.trim(), argv, timeout, form.cwd.trim())
        if (errors.isNotEmpty()) {
            _submitForm.update { it.copy(errors = errors) }
            return
        }
        _submitForm.update { it.copy(submitting = true, errors = emptyList()) }
        viewModelScope.launch {
            val request = SubmitRequest(
                name = form.name.trim().ifEmpty { argv.firstOrNull()?.substringAfterLast('/') ?: "job" },
                type = "argv",
                argv = argv,
                cwd = form.cwd.trim().ifEmpty { null },
                env = null,
                timeout_s = timeout,
                idempotency_key = UUID.randomUUID().toString(),
                // §3.2: SubmitDialog only invokes this after its own
                // second-stage "Run this command?" confirmation
                confirm = true,
            )
            when (val r = client().submitJob(request)) {
                is BridgeResult.Success -> {
                    _submitForm.value = SubmitFormState()
                    _dashboard.update {
                        it.copy(notice = "job ${r.data.response.job_id} submitted" +
                            if (r.data.replayed) " (idempotent replay)" else "")
                    }
                    refresh()
                }
                is BridgeResult.HttpError -> _submitForm.update {
                    it.copy(submitting = false,
                            errors = listOf(SubmitValidator.FieldError(r.code, r.message)))
                }
                is BridgeResult.NetworkError -> _submitForm.update {
                    it.copy(submitting = false,
                            errors = listOf(SubmitValidator.FieldError(
                                "network", "bridge unreachable: ${r.cause.message}")))
                }
            }
        }
    }

    // ---- job detail + cancel --------------------------------------------

    fun selectJob(jobId: String?) {
        pollJob?.cancel()
        outputJob?.cancel()
        if (jobId == null) {
            _detail.value = null
            _output.value = null
        } else {
            _detail.value = JobDetailState(jobId)
            _output.value = OutputReducer.initial()
            _artifacts.value = ArtifactsState(jobId = jobId)
            maybeLoadArtifacts(jobId)
            startOutput(jobId)
            pollJob = viewModelScope.launch {
                while (isActive) {
                    when (val r = client().getJob(jobId)) {
                        is BridgeResult.Success -> {
                            _detail.update { it?.copy(job = r.data, error = null) }
                            if (r.data.status in TERMINAL) break
                        }
                        is BridgeResult.HttpError -> {
                            _detail.update { it?.copy(error = "HTTP ${r.status}: ${r.code}") }
                            break
                        }
                        is BridgeResult.NetworkError -> {
                            _detail.update { it?.copy(error = "bridge unreachable: ${r.cause.message}") }
                        }
                    }
                    delay(DETAIL_POLL_MS)
                }
            }
        }
    }

    fun cancelJob(jobId: String) {
        _detail.update { it?.copy(cancelling = true) }
        viewModelScope.launch {
            when (val r = client().cancelJob(jobId)) {
                is BridgeResult.Success -> {
                    _detail.update { it?.copy(job = r.data, cancelling = false) }
                    _dashboard.update { d ->
                        val idx = d.jobs.indexOfFirst { it.job_id == r.data.job_id }
                        val jobs = if (idx >= 0) {
                            d.jobs.toMutableList().also { it[idx] = r.data }
                        } else d.jobs
                        d.copy(jobs = jobs)
                    }
                }
                is BridgeResult.HttpError -> {
                    _detail.update { it?.copy(cancelling = false, error = "HTTP ${r.status}: ${r.code} — ${r.message}") }
                    _dashboard.update { it.copy(error = "cancel failed: HTTP ${r.status}: ${r.code}") }
                }
                is BridgeResult.NetworkError -> {
                    _detail.update { it?.copy(cancelling = false, error = "bridge unreachable: ${r.cause.message}") }
                    _dashboard.update { it.copy(error = "cancel failed: bridge unreachable") }
                }
            }
        }
    }



    // ---- tools & recipes (Phase 9) --------------------------------------

    fun loadTools() {
        if (_tools.value.loading) return
        _tools.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = client().listTools()) {
                is BridgeResult.Success -> {
                    val schemaErrors = _dashboard.value.info?.features?.tool_schema_errors
                        ?: emptyList()
                    _tools.update {
                        it.copy(loading = false,
                                tools = r.data.tools.filterNot { t -> t.schema.id == "agent" },
                                schemaErrors = schemaErrors)
                    }
                }
                is BridgeResult.HttpError ->
                    _tools.update { it.copy(loading = false,
                                            error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _tools.update { it.copy(loading = false,
                                            error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    fun refreshToolsNow() {
        _tools.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = client().refreshTools()) {
                is BridgeResult.Success -> _tools.update {
                    val visible = r.data.tools.filterNot { t -> t.schema.id == "agent" }
                    it.copy(loading = false, tools = visible,
                            notice = "rescan complete — ${visible.size} tools")
                }
                is BridgeResult.HttpError ->
                    _tools.update { it.copy(loading = false,
                                            error = "HTTP ${r.status} ${r.code}") }
                is BridgeResult.NetworkError ->
                    _tools.update { it.copy(loading = false,
                                            error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    /** Self-healing toolbox: run `pkg install -y <pkg>` as a normal job and
     *  rescan when it finishes (watched via /v1/events, see onEvent). */
    fun installTool(t: ToolStatus) {
        val pkg = t.schema.pkg ?: return
        viewModelScope.launch {
            when (val r = client().submitJob(dev.trmx.gui.model.SubmitRequest(
                name = "install ${pkg}", type = "argv",
                argv = listOf("pkg", "install", "-y", pkg), cwd = "~",
                // §3.2: the toolbox's Install dialog already confirmed this
                confirm = true))) {
                is BridgeResult.Success -> {
                    installWatch = r.data.response.job_id
                    _tools.update {
                        it.copy(notice = "installing ${pkg} (job ${r.data.response.job_id}) — "
                                + "the toolbox rescans automatically when it finishes")
                    }
                }
                is BridgeResult.HttpError ->
                    _tools.update { it.copy(error = "install failed: HTTP ${r.status} ${r.code}") }
                is BridgeResult.NetworkError ->
                    _tools.update { it.copy(error = "install failed: ${r.cause.message}") }
            }
        }
    }

    // ---- dynamic form ------------------------------------------------------

    fun openToolForm(toolId: String,
                     preset: Map<String, JsonElement>? = null) {
        val schema = _tools.value.tools.firstOrNull { it.schema.id == toolId }?.schema
        if (schema == null) {
            _tools.update { it.copy(error = "tool not found: $toolId") }
            return
        }
        var values = FormEngine.initialValues(schema)
        preset?.forEach { (k, v) ->
            val spec = schema.args.firstOrNull { it.name == k } ?: return@forEach
            val text = runCatching { v.jsonPrimitive.content }.getOrNull()
            values = values + (k to if (spec.type == "bool")
                FieldValue(bool = text == "true" || text == "1") else FieldValue(text = text ?: ""))
        }
        _toolForm.value = ToolFormState(schema = schema, values = values,
                                      touched = preset?.keys?.toSet() ?: emptySet())
    }

    fun closeToolForm() { _toolForm.value = ToolFormState() }

    /**
     * Home intent cards (P2): open a tool form even when the registry is
     * cold (Home renders before the Toolbox ever loads). Cached schema →
     * straight to the form; otherwise fetch, merge into the registry, open.
     */
    fun openToolById(toolId: String) {
        if (_tools.value.tools.any { it.schema.id == toolId }) {
            openToolForm(toolId); return
        }
        viewModelScope.launch {
            when (val res = client().getTool(toolId)) {
                is BridgeResult.Success -> {
                    _tools.update { t ->
                        if (t.tools.none { it.schema.id == toolId })
                            t.copy(tools = t.tools + res.data) else t
                    }
                    openToolForm(toolId)
                }
                else -> _dashboard.update {
                    it.copy(notice = "tool unavailable: $toolId — try Tools → Scan")
                }
            }
        }
    }

    fun editFieldValue(arg: String, value: FieldValue) {
        _toolForm.update { it.copy(values = it.values + (arg to value),
                                   touched = it.touched + arg) }
    }

    /** Run attempted: surface every field's validation state (Ph 9.5). */
    fun touchAllFields() {
        _toolForm.update { f -> f.copy(touched = f.values.keys) }
    }

    /** Path args are picked in the Files browser (Phase 7) — never typed. */
    fun pickPathFor(arg: String) { _toolForm.update { it.copy(pathArg = arg) } }
    fun pathPicked(path: String) {
        val arg = _toolForm.value.pathArg ?: return
        _toolForm.update { it.copy(values = it.values + (arg to FieldValue(text = path)),
                                   pathArg = null) }
    }
    fun cancelPathPick() { _toolForm.update { it.copy(pathArg = null) } }

    fun runCommandLine(commandLine: String) {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return
        val argv = parseCommandLineTokens(trimmed)
        if (argv.isEmpty()) return
        viewModelScope.launch {
            val req = SubmitRequest(
                name = argv.firstOrNull()?.substringAfterLast('/') ?: "command",
                type = "argv",
                argv = argv,
                cwd = "~",
                env = null,
                timeout_s = null,
                idempotency_key = UUID.randomUUID().toString(),
                confirm = true,
            )
            when (val r = client().submitJob(req)) {
                is BridgeResult.Success -> {
                    val id = r.data.response.job_id
                    _dashboard.update {
                        it.copy(notice = "job $id started")
                    }
                    refresh()
                    selectJob(id)
                }
                is BridgeResult.HttpError -> {
                    _dashboard.update {
                        it.copy(error = "HTTP ${r.status}: ${r.message}")
                    }
                }
                is BridgeResult.NetworkError -> {
                    _dashboard.update {
                        it.copy(error = "bridge unreachable: ${r.cause.message}")
                    }
                }
            }
        }
    }

    private fun parseCommandLineTokens(cmd: String): List<String> {
        val tokens = mutableListOf<String>()
        var sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (c in cmd) {
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb = StringBuilder()
                    }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    fun submitToolForm() {
        val f = _toolForm.value
        val schema = f.schema ?: return
        if (!FormEngine.isSubmittable(schema, f.values)) {
            _toolForm.update { it.copy(error = "some fields need attention first",
                                       touched = f.values.keys) }
            return
        }
        val args = FormEngine.argsPayload(schema, f.values)
        // One live job per tool. The Run button used to stack duplicates
        // without a word — for a server-style tool that is a port war
        // (device lesson, 2026-10-19: a pile of llama-server jobs racing
        // :8080 while the owner thought nothing had started).
        val running = _dashboard.value.jobs.firstOrNull {
            it.tool == schema.id && (it.status == "RUNNING" || it.status == "STARTING" || it.status == "QUEUED")
        }
        if (running != null) {
            _toolForm.update {
                it.copy(error = null,
                        notice = "already running — job ${running.job_id}. " +
                            "Cancel it from Work if you want a fresh start.")
            }
            return
        }
        _toolForm.update { it.copy(submitting = true, error = null, notice = null) }
        viewModelScope.launch {
            val req = ToolSubmitRequest(
                name = schema.name.ifBlank { schema.id },
                tool = schema.id, args = args, cwd = "~")
            when (val r = client().submitToolJob(req)) {
                is BridgeResult.Success -> {
                    val id = r.data.response.job_id
                    toolJobMeta[id] = Artifacts.metaFor(schema, args)
                    _toolForm.value = ToolFormState()
                    _dashboard.update {
                        it.copy(notice = "job $id submitted (${schema.id})")
                    }
                    refresh()
                    selectJob(id)
                }
                is BridgeResult.HttpError ->
                    _toolForm.update { it.copy(submitting = false,
                                               error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _toolForm.update { it.copy(submitting = false,
                                               error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    // ---- AI Schema Builder (AI round, ADR-014) ------------------------------

    /** Runs the bundled `ai-schema-builder` tool (trmx-ai on the phone);
     *  completion is watched via /v1/events — see onEvent. */
    fun editSchemaDescription(v: String) =
        _schemaBuilder.update { it.copy(description = v, error = null) }

    fun clearSchemaBuilderResult() =
        _schemaBuilder.update { it.copy(result = null, error = null, jobId = null) }

    fun submitSchemaBuilder() {
        val s = _schemaBuilder.value
        if (s.submitting) return
        val desc = s.description.trim()
        if (desc.length < 8) {
            _schemaBuilder.update {
                it.copy(error = "describe the tool in a few more words (name the binary)")
            }
            return
        }
        _schemaBuilder.update { it.copy(submitting = true, error = null, result = null) }
        viewModelScope.launch {
            val req = ToolSubmitRequest(
                name = "AI schema build",
                tool = "ai-schema-builder",
                args = mapOf("description" to JsonPrimitive(desc)),
                cwd = "~")
            when (val r = client().submitToolJob(req)) {
                is BridgeResult.Success -> {
                    val id = r.data.response.job_id
                    aiWatch = id
                    _schemaBuilder.update { it.copy(submitting = false, jobId = id) }
                    _dashboard.update {
                        it.copy(notice = "job $id submitted (ai-schema-builder)")
                    }
                }
                is BridgeResult.HttpError ->
                    _schemaBuilder.update {
                        it.copy(submitting = false,
                                error = "HTTP ${r.status} ${r.code} — ${r.message}")
                    }
                is BridgeResult.NetworkError ->
                    _schemaBuilder.update {
                        it.copy(submitting = false,
                                error = "bridge unreachable: ${r.cause.message}")
                    }
            }
        }
    }


    // ---- services (protocol 1.1, §14) ----------------------------------------

    /** Capability flag from the last system/info (null = not fetched yet). */
    fun noteServiceSupport(supported: Boolean?) {
        _services.update { it.copy(supported = supported) }
    }

    fun loadServices() {
        if (_services.value.loading) return
        _services.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = client().listServices()) {
                is BridgeResult.Success -> {
                    _services.update {
                        it.copy(loading = false, services = r.data.services,
                                supported = true)
                    }
                }
                is BridgeResult.HttpError ->
                    _services.update {
                        if (r.status == 404) {
                            it.copy(loading = false, supported = false, error = null)
                        } else {
                            it.copy(loading = false, error = "HTTP ${r.status} ${r.code} — ${r.message}")
                        }
                    }
                is BridgeResult.NetworkError ->
                    _services.update { it.copy(loading = false,
                                               error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    fun clearServicesNotice() = _services.update { it.copy(notice = null, error = null) }

    private fun applyServiceResult(r: BridgeResult<ServiceStatus>, what: String) {
        when (r) {
            is BridgeResult.Success -> {
                val st = r.data
                _services.update { s ->
                    s.copy(notice = "${st.name}: $what",
                           services = s.services.map {
                               if (it.id == st.id) st else it
                           })
                }
            }
            is BridgeResult.HttpError ->
                _services.update { it.copy(error = "HTTP ${r.status} ${r.code} — ${r.message}") }
            is BridgeResult.NetworkError ->
                _services.update { it.copy(error = "bridge unreachable: ${r.cause.message}") }
        }
    }

    fun startService(id: String) {
        viewModelScope.launch { applyServiceResult(client().startService(id), "started") }
    }

    fun stopService(id: String) {
        viewModelScope.launch { applyServiceResult(client().stopService(id), "stopping…") }
    }

    fun restartService(id: String) {
        viewModelScope.launch { applyServiceResult(client().restartService(id), "restarted") }
    }

    fun setServiceAutostart(id: String, enabled: Boolean) {
        viewModelScope.launch {
            applyServiceResult(client().setServiceAutostart(id, enabled),
                               if (enabled) "autostart on" else "autostart off")
        }
    }

    fun deleteService(id: String) {
        viewModelScope.launch {
            when (val r = client().deleteService(id)) {
                is BridgeResult.Success -> {
                    _services.update { s ->
                        s.copy(notice = "service deleted",
                               services = s.services.filterNot { it.id == id })
                    }
                }
                is BridgeResult.HttpError ->
                    _services.update { it.copy(error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _services.update { it.copy(error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    /** Save the CURRENT tool form as a service definition (§14): the form's
     *  validated args become the service's fixed args. Long-running tools
     *  (http-server & friends) are the natural fit, but any tool works. */
    fun saveServiceFromForm() {
        val f = _toolForm.value
        val schema = f.schema ?: return
        if (!FormEngine.isSubmittable(schema, f.values)) {
            _toolForm.update { it.copy(error = "fill the required fields first",
                                       touched = f.values.keys) }
            return
        }
        val args = FormEngine.argsPayload(schema, f.values)
        val sid = schema.id.replace(Regex("[^A-Za-z0-9._-]"), "-")
        viewModelScope.launch {
            when (val r = client().createService(
                    ServiceDefRequest(id = sid, name = schema.name.ifBlank { sid },
                                      tool = schema.id, args = args))) {
                is BridgeResult.Success -> {
                    _toolForm.update {
                        it.copy(notice = "saved as service \"${r.data.id}\" — manage it in Services")
                    }
                    _services.update { it.copy(notice = "service \"${r.data.name}\" created") }
                }
                is BridgeResult.HttpError ->
                    _toolForm.update { it.copy(error = "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _toolForm.update { it.copy(error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    // ---- artifacts (Phase 9.5) ---------------------------------------------

    /**
     * Derive artifacts for a COMPLETED tool job: exact outputs from the
     * submit-time meta (or schema defaults after an app restart) plus files
     * newly detected in the outdir (mtime >= started_at, labeled "detected").
     */
    private fun maybeLoadArtifacts(jobId: String) {
        viewModelScope.launch {
            val job = when (val r = client().getJob(jobId)) {
                is BridgeResult.Success -> r.data
                else -> return@launch
            }
            if (job.type != "tool" || job.tool == null || job.status != "COMPLETED") return@launch
            val schema = _tools.value.tools.firstOrNull { it.schema.id == job.tool }?.schema
            val meta = toolJobMeta[jobId]
                ?: (schema?.let { Artifacts.metaFor(it, emptyMap()) })   // post-restart fallback
                ?: return@launch
            if (meta.outputs.isEmpty() && meta.outdir == null) return@launch
            _artifacts.update { it.copy(jobId = jobId, loading = true, error = null) }
            var listing: List<FileEntry>? = null
            if (meta.outdir != null) {
                when (val r = client().listFiles(meta.outdir!!)) {
                    is BridgeResult.Success -> listing = r.data.entries
                    is BridgeResult.HttpError ->
                        _artifacts.update { it.copy(loading = false,
                            error = "could not list ${meta.outdir}: HTTP ${r.status} ${r.code}") }
                    is BridgeResult.NetworkError -> Unit   // exact outputs still shown
                }
            }
            val arts = Artifacts.collect(meta, job.started_at, listing, meta.outdir)
            _artifacts.update { it.copy(loading = false, artifacts = arts) }
        }
    }


    // ---- recipes -------------------------------------------------------------

    fun saveRecipeFromForm(title: String) {
        val f = _toolForm.value
        val schema = f.schema ?: return
        if (!FormEngine.isSubmittable(schema, f.values)) {
            _toolForm.update { it.copy(error = "fix the fields before saving a recipe") }
            return
        }
        val recipe = Recipe(RecipeStore.newId(), title.ifBlank { schema.name },
                            schema.id, FormEngine.argsPayload(schema, f.values),
                            System.currentTimeMillis())
        recipeStore.add(recipe)
        _recipes.value = recipeStore.list()
        refreshShortcuts()
        _toolForm.update { it.copy(notice = "recipe saved: ${recipe.title}") }
    }

    fun deleteRecipe(id: String) {
        recipeStore.remove(id)
        _recipes.value = recipeStore.list()
        refreshShortcuts()
    }

    /** Cold-start entry (home-screen shortcut): fetch the schema, then open. */
    fun openRecipe(id: String) {
        val r = recipeStore.byId(id) ?: return
        val cached = _tools.value.tools.firstOrNull { it.schema.id == r.toolId }?.schema
        if (cached != null) { openToolForm(r.toolId, preset = r.args); return }
        viewModelScope.launch {
            when (val res = client().getTool(r.toolId)) {
                is BridgeResult.Success -> {
                    _tools.update { t ->
                        if (t.tools.none { it.schema.id == r.toolId })
                            t.copy(tools = t.tools + res.data) else t
                    }
                    openToolForm(r.toolId, preset = r.args)
                }
                else -> _tools.update { it.copy(error = "recipe's tool is unavailable: ${r.toolId}") }
            }
        }
    }

    /** Recipe-share failure surface: FilesViewModel.shareRecipe (which
     *  owns the staged file since the split) reports through this
     *  adapter — the same _tools update the pre-split shareRecipe
     *  performed inline. */
    fun reportToolError(message: String) =
        _tools.update { it.copy(error = message) }

    fun importRecipe(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw java.io.IOException("cannot open picked file")
                val parsed = RecipeStore.import(text)
                    ?: throw IllegalArgumentException("not a valid TRMX recipe")
                val r = parsed.copy(id = RecipeStore.newId())   // never hijack ids
                recipeStore.add(r)
                _recipes.value = recipeStore.list()
                refreshShortcuts()
                _tools.update { it.copy(error = null,
                                         notice = "imported recipe: ${r.title}") }
            }.onFailure { e ->
                _tools.update { it.copy(error = "import failed: ${e.message}") }
            }
        }
    }

    /** Long-press TRMX → one-tap recipes (dynamic shortcuts, newest 4). */
    fun refreshShortcuts() {
        val context = getApplication<Application>()
        runCatching {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            val infos = recipeStore.list()
                .sortedByDescending { it.createdAt }
                .take(4)
                .map { r ->
                    ShortcutInfoCompat.Builder(context, r.id)
                        .setShortLabel(r.title.take(12))
                        .setLongLabel(r.title.take(30))
                        .setIntent(Intent(context, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .putExtra("recipe_id", r.id))
                        .build()
                }
            if (infos.isNotEmpty()) ShortcutManagerCompat.addDynamicShortcuts(context, infos)
        }
    }

    // ---- live output stream (Phase 6) -----------------------------------

    /**
     * Open /v1/jobs/{id}/output with full replay (from_seq=1) + live follow.
     * On connection loss the stream resumes from the last delivered seq
     * (the bridge's from_seq replay makes resume seamless); a normal close
     * after a terminal status frame ends collection.
     */
    private fun startOutput(jobId: String) {
        outputJob?.cancel()
        outputJob = viewModelScope.launch {
            var fromSeq = 1L
            var attempts = 0
            while (isActive) {
                try {
                    sseClient()
                        .stream("/v1/jobs/$jobId/output?from_seq=$fromSeq&follow=1&stream=both")
                        .collect { frame ->
                            attempts = 0
                            _output.value = OutputReducer.apply(
                                _output.value ?: OutputReducer.initial(), frame)
                            if (frame.event == "status") mergeStatusIntoDetail(frame.data)
                            fromSeq = (_output.value?.lastSeq ?: (fromSeq - 1)) + 1
                        }
                    // stream ended normally — done if a terminal status came through
                    if (_output.value?.ended == true) return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // connection lost — retry below
                }
                attempts++
                if (attempts >= 30) {
                    _output.update { it?.copy(error = "stream lost after $attempts reconnect attempts") }
                    return@launch
                }
                delay(1_000L)
            }
        }
    }

    /** Re-open the output stream from seq 1 (fresh replay). */
    fun replayOutput() {
        val id = _detail.value?.jobId ?: return
        _output.value = OutputReducer.initial()
        startOutput(id)
    }

    /** Live status frames also refresh the job detail header. */
    private fun mergeStatusIntoDetail(data: String) {
        val el = runCatching {
            BridgeClient.jsonFormat.parseToJsonElement(data).jsonObject
        }.getOrNull() ?: return
        fun str(k: String) = el[k]?.jsonPrimitive?.contentOrNull
        _detail.update { d ->
            d?.copy(job = d.job?.copy(
                status = str("status") ?: d.job.status,
                exit_code = el["exit_code"]?.jsonPrimitive?.longOrNull ?: d.job.exit_code,
                ended_at = str("ended_at") ?: d.job.ended_at,
                progress_pct = el["progress_pct"]?.jsonPrimitive?.doubleOrNull
                    ?: d.job.progress_pct,
                progress_detail = str("progress_detail") ?: d.job.progress_detail,
            ))
        }
    }

    // ---- global event stream (Phase 6) ----------------------------------

    /**
     * Subscribe to /v1/events while the app is foregrounded (MainActivity
     * stops this loop on ON_STOP). Reconnects back off exponentially —
     * 2 s → 30 s cap (§6-H4): with the bridge down the old flat 2 s retry
     * burned a connection attempt every 2 s for as long as the app was
     * open. A stream that connected resets the backoff, so a healthy
     * stream that drops still reconnects at the fast 2 s pace.
     */
    fun startEvents() {
        if (eventsJob?.isActive == true) return
        eventsJob = viewModelScope.launch {
            val backoff = SseBackoff()
            while (isActive) {
                var connected = false
                try {
                    sseClient().stream("/v1/events").collect { frame ->
                        connected = true
                        onEvent(frame)
                    }
                    connected = true   // clean close ⇒ the stream did connect
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // bridge stopped or connection lost — back off below
                }
                if (connected) backoff.reset()
                delay(backoff.nextDelayMs())
            }
        }
    }

    fun stopEvents() {
        eventsJob?.cancel()
        eventsJob = null
    }

    private fun onEvent(frame: SseFrame) {
        when (frame.event) {
            "job.updated" -> {
                val job = runCatching {
                    BridgeClient.jsonFormat.decodeFromString(JobSummary.serializer(), frame.data)
                }.getOrNull() ?: return
                _dashboard.update { d ->
                    val idx = d.jobs.indexOfFirst { it.job_id == job.job_id }
                    val jobs = if (idx >= 0) {
                        d.jobs.toMutableList().also { it[idx] = job }
                    } else {
                        listOf(job) + d.jobs
                    }
                    d.copy(jobs = jobs)
                }
                if (_detail.value?.jobId == job.job_id) {
                    _detail.update { it?.copy(job = job, error = null) }
                }
                // self-healing toolbox: a finished pkg install triggers a rescan
                if (installWatch == job.job_id && job.status in TERMINAL) {
                    installWatch = null
                    _tools.update { it.copy(notice =
                        "install ${if (job.status == "COMPLETED") "finished" else job.status.lowercase()} — rescanning") }
                    refreshToolsNow()
                }
                // protocol 1.1: a bound service job changed state — refresh
                // that service's derived status without a round trip
                val bound = _services.value.services.firstOrNull { it.job_id == job.job_id }
                if (bound != null) {
                    _services.update { s ->
                        s.copy(services = s.services.map {
                            if (it.id == bound.id) it.copy(
                                state = if (job.status in TERMINAL) "stopped" else "running",
                                last_status = job.status,
                                last_exit_code = job.exit_code?.toInt(),   // Long? -> Int?
                                job_id = if (job.status in TERMINAL) null else job.job_id)
                            else it
                        })
                    }
                }
                // AI round: a finished trmx-ai job installed a schema —
                // rescan the registry so the new tool appears immediately
                if (aiWatch == job.job_id && job.status in TERMINAL) {
                    aiWatch = null
                    if (job.status == "COMPLETED") {
                        _schemaBuilder.update {
                            it.copy(result = "schema installed — toolbox rescanned")
                        }
                        refreshToolsNow()
                    } else {
                        _schemaBuilder.update {
                            it.copy(result = "job ${job.status.lowercase()} — open it for details")
                        }
                    }
                }
            }
            "service.updated" -> {
                // protocol 1.1: replace/delete the entry in place
                val st = runCatching {
                    BridgeClient.jsonFormat.decodeFromString(ServiceStatus.serializer(), frame.data)
                }.getOrNull()
                if (st != null) {
                    _services.update { s ->
                        when {
                            st.deleted -> s.copy(services = s.services.filterNot { it.id == st.id })
                            s.services.any { it.id == st.id } ->
                                s.copy(services = s.services.map { if (it.id == st.id) st else it })
                            else -> s.copy(services = s.services + st)
                        }
                    }
                }
            }
            "bridge.stopping" -> {
                val reason = runCatching {
                    BridgeClient.jsonFormat.parseToJsonElement(frame.data)
                        .jsonObject["reason"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                _dashboard.update {
                    it.copy(notice = "bridge stopping (${reason ?: "unknown"}) — will reconnect")
                }
            }
        }
    }

    // ---- dashboard ------------------------------------------------------

    fun refresh() {
        _dashboard.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            val client = client()
            when (val info = client.systemInfo()) {
                is BridgeResult.Success -> {
                    _dashboard.update { it.copy(info = info.data) }
                    // protocol 1.1 capability flag (§14) — drives the Services tab
                    noteServiceSupport(info.data.features?.service_registry)
                }
                is BridgeResult.HttpError -> {
                    _dashboard.update { it.copy(error = "HTTP ${info.status}: ${info.code}", refreshing = false) }
                    return@launch
                }
                is BridgeResult.NetworkError -> {
                    _dashboard.update { it.copy(error = "bridge unreachable: ${info.cause.message}", refreshing = false) }
                    return@launch
                }
            }
            when (val jobs = client.listJobs()) {
                is BridgeResult.Success ->
                    _dashboard.update { it.copy(jobs = jobs.data.jobs, refreshing = false) }
                is BridgeResult.HttpError ->
                    _dashboard.update { it.copy(error = "HTTP ${jobs.status}: ${jobs.code}", refreshing = false) }
                is BridgeResult.NetworkError ->
                    _dashboard.update { it.copy(error = "jobs unreachable: ${jobs.cause.message}", refreshing = false) }
            }
        }
    }

    fun stopBridge() {
        controlPlane.send(ControlOps.spec("STOP")!!)
        _dashboard.update { it.copy(notice = "stop requested — the bridge is shutting down") }
        viewModelScope.launch {
            delay(2_000)
            refresh()
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        outputJob?.cancel()
        eventsJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val DETAIL_POLL_MS = 2_000L
        private val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "LOST")
    }
}
