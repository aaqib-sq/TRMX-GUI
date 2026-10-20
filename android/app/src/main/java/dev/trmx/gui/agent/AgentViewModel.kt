package dev.trmx.gui.agent

/*
 * AgentScreen's viewmodel (revival §5): owns the whole agent-task
 * lifecycle — POST /v1/agent/tasks, the §5 event stream off
 * GET /v1/jobs/{job_id}/output?follow=1, confirm Allow/Deny, and Stop.
 * The active AI backend (mode + model, free vs API cost) comes from
 * GET /v1/ai/config and is shown on the screen so every step's cost is
 * honest before the user sends anything.
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.model.AiConfig
import dev.trmx.gui.model.AgentTaskStatus
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.SseClient
import dev.trmx.gui.store.AgentSession
import dev.trmx.gui.store.AgentSessionStore
import dev.trmx.gui.store.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

enum class AgentRowKind {
    BACKEND, THINKING, DOING, RESULT_OK, RESULT_FAIL, NOTE, DONE, FAILED
}

data class AgentRow(
    val kind: AgentRowKind,
    val title: String,
    val detail: String? = null,
)

data class AgentConfirm(
    val describe: String,
    val tool: String,
    val why: String,
)

data class AgentState(
    val backendLabel: String? = null,   // "cli · ollama run llama3.2"
    val backendFree: Boolean? = null,   // true = local/free, false = API money
    val backendConfigured: Boolean = true,
    val backendLoading: Boolean = false,
    val input: String = "",
    val sending: Boolean = false,
    val rows: List<AgentRow> = emptyList(),
    val confirm: AgentConfirm? = null,
    val taskId: String? = null,
    val jobId: String? = null,
    val active: Boolean = false,
    val streamError: String? = null,
    val sendError: String? = null,
    val confirmBusy: Boolean = false,
)

class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TokenStore(app)
    private val sessionStore = AgentSessionStore(app.filesDir)
    private val http = OkHttpClient()

    private val _state = MutableStateFlow(AgentState())
    val state = _state.asStateFlow()

    private var streamJob: Job? = null
    private var restoring = false      // restore in flight
    private var restoreTried = false   // definitive restore outcome reached

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

    // ---- backend ---------------------------------------------------------

    /** Which backend answers the agent's LLM calls — the user sees this
     *  before sending, so "API money or free/local" is never a surprise. */
    fun loadBackend() {
        if (_state.value.backendLabel != null || _state.value.backendLoading) return
        _state.update { it.copy(backendLoading = true) }
        viewModelScope.launch {
            when (val r = client().getAiConfig()) {
                is BridgeResult.Success -> {
                    val c: AiConfig = r.data
                    val (label, free) = when (c.mode) {
                        "http_api" -> {
                            val p = c.http_api?.provider?.ifEmpty { null } ?: "api"
                            val m = c.http_api?.model?.ifEmpty { null } ?: ""
                            val ep = c.http_api?.endpoint ?: ""
                            val local = ep.contains("127.0.0.1") || ep.contains("localhost")
                            ("http_api · $p" + if (m.isNotEmpty()) " · $m" else "") to local
                        }
                        else -> {
                            val cmd = c.cli?.command?.joinToString(" ")
                                ?.ifEmpty { null } ?: "local model"
                            ("cli · $cmd") to true
                        }
                    }
                    _state.update {
                        it.copy(backendLabel = label, backendFree = free,
                                backendConfigured = c.exists, backendLoading = false)
                    }
                }
                is BridgeResult.HttpError ->
                    _state.update { it.copy(backendLoading = false) }
                is BridgeResult.NetworkError ->
                    _state.update { it.copy(backendLoading = false) }
            }
        }
    }

    // ---- send ------------------------------------------------------------

    fun inputChanged(v: String) =
        _state.update { it.copy(input = v, sendError = null) }

    fun clearSendError() = _state.update { it.copy(sendError = null) }

    fun send() {
        val instruction = _state.value.input.trim()
        if (_state.value.sending || _state.value.active) return
        if (instruction.isEmpty() || instruction.length > 2000) {
            _state.update { it.copy(sendError = "Write an instruction first (1–2000 characters).") }
            return
        }
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            when (val r = client().createAgentTask(instruction)) {
                is BridgeResult.Success -> {
                    // Persist BEFORE streaming: if Android kills the app
                    // mid-task, restoreSession() picks this task back up.
                    sessionStore.save(AgentSession(
                        taskId = r.data.task_id, jobId = r.data.job_id,
                        instruction = instruction,
                        startedAt = System.currentTimeMillis()))
                    _state.update {
                        it.copy(
                            sending = false, input = "",
                            rows = emptyList(), confirm = null,
                            taskId = r.data.task_id, jobId = r.data.job_id,
                            active = true, streamError = null)
                    }
                    startStream(r.data.job_id)
                }
                is BridgeResult.HttpError -> {
                    val msg = when (r.status) {
                        404 -> "This bridge doesn't support the agent yet — " +
                            "update the bridge first."
                        401 -> "The bridge rejected this app's token. " +
                            "Repair the connection from the Work tab."
                        else -> "The bridge answered with an error " +
                            "(HTTP ${r.status})."
                    }
                    _state.update { it.copy(sending = false, sendError = msg) }
                }
                is BridgeResult.NetworkError ->
                    _state.update {
                        it.copy(sending = false,
                                sendError = "Can't reach the bridge — it may " +
                                    "be stopped. In Termux run: ~/.trmx/trmx start")
                    }
            }
        }
    }

    // ---- restore after process death (Arena audit finding 6) ---------------

    /**
     * Re-attach to the task [send] persisted, after Android killed the app
     * process mid-task. The bridge kept the task (and its job output)
     * alive: GET /v1/agent/tasks/{id} reports the task's current state —
     * including a pending confirm and the final summary/error — and a
     * still-running task's output stream replays from seq 1, so the
     * transcript, the Allow/Deny card and Stop all come back.
     *
     * Runs at most once per process; a bridge that is merely down leaves
     * the saved session in place so a later visit to the Agent tab retries.
     */
    fun restoreSession() {
        if (restoring || restoreTried) return
        if (_state.value.taskId != null) return   // this VM already has a task
        val session = sessionStore.load()
        if (session == null) {
            restoreTried = true
            return
        }
        restoring = true
        viewModelScope.launch {
            try {
                when (val r = client().agentTaskStatus(session.taskId)) {
                    is BridgeResult.Success -> {
                        restoreTried = true
                        applyRestored(r.data)
                    }
                    is BridgeResult.HttpError -> {
                        if (r.status == 404) {
                            // the bridge no longer knows this task — the
                            // saved pointer is stale for good
                            sessionStore.clear()
                            restoreTried = true
                        }
                    }
                    is BridgeResult.NetworkError -> Unit   // retry on a later visit
                }
            } finally {
                restoring = false
            }
        }
    }

    private fun applyRestored(st: AgentTaskStatus) {
        val note = AgentRow(AgentRowKind.NOTE,
            title = "Restored — the app had closed; this is the task " +
                "you started earlier.")
        when (st.status) {
            "done" -> _state.update {
                it.copy(taskId = st.task_id, jobId = st.job_id,
                        rows = listOf(note, AgentRow(
                            AgentRowKind.DONE,
                            title = "Done" +
                                (st.summary?.takeIf { s -> s.isNotEmpty() }
                                    ?.let { s -> ": $s" } ?: ""))))
            }
            "failed" -> _state.update {
                it.copy(taskId = st.task_id, jobId = st.job_id,
                        rows = listOf(note, AgentRow(
                            AgentRowKind.FAILED,
                            title = "Stopped" +
                                (st.error?.takeIf { s -> s.isNotEmpty() }
                                    ?.let { s -> ": ${humanAgentError(s)}" }
                                    ?: ""))))
            }
            else -> {
                // running / awaiting_confirm: re-attach the stream — the
                // replay rebuilds the transcript (and re-raises a pending
                // confirm with its full detail); Stop works again.
                val confirm = if (st.awaiting_confirm && st.pending_action != null)
                    AgentConfirm(describe = st.pending_action.describe,
                                 tool = st.pending_action.tool ?: "",
                                 why = "")
                else null
                _state.update {
                    it.copy(taskId = st.task_id, jobId = st.job_id,
                            rows = listOf(note), confirm = confirm,
                            active = true, streamError = null)
                }
                startStream(st.job_id)
            }
        }
    }

    // ---- event stream ------------------------------------------------------

    private fun startStream(jobId: String) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var fromSeq = 1L
            var attempts = 0
            var terminalSeen = false
            while (isActive && !terminalSeen) {
                try {
                    SseClient(store.bridgeBaseUrl, store.token, http)
                        .stream("/v1/jobs/$jobId/output?from_seq=$fromSeq&follow=1")
                        .collect { frame ->
                            when (frame.event) {
                                "stdout" -> {
                                    val text = runCatching<String?> {
                                        BridgeClient.jsonFormat
                                            .parseToJsonElement(frame.data)
                                            .jsonObject["text"]
                                            ?.jsonPrimitive?.contentOrNull
                                    }.getOrNull() ?: frame.data
                                    if (!text.isNullOrBlank()) onAgentText(text)
                                    frame.id?.let { fromSeq = maxOf(fromSeq, it + 1) }
                                }
                                "status" -> {
                                    val status = runCatching<String?> {
                                        BridgeClient.jsonFormat
                                            .parseToJsonElement(frame.data)
                                            .jsonObject["status"]
                                            ?.jsonPrimitive?.contentOrNull
                                    }.getOrNull()
                                    frame.id?.let { fromSeq = maxOf(fromSeq, it + 1) }
                                    if (!status.isNullOrEmpty() && status in TERMINAL_STATUSES) {
                                        terminalSeen = true
                                        _state.update { it.copy(active = false) }
                                    }
                                }
                                else -> {
                                    frame.id?.let { fromSeq = maxOf(fromSeq, it + 1) }
                                }
                            }
                        }
                    // stream ended on its own — stop if the task went quiet
                    if (!terminalSeen) {
                        _state.update { it.copy(active = false) }
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // connection lost — retry below
                }
                attempts++
                if (attempts >= 30) {
                    _state.update {
                        it.copy(streamError =
                            "Lost the live stream after $attempts tries — " +
                                "the task may still be running.")
                    }
                    return@launch
                }
                delay(1_000L)
            }
        }
    }

    private fun onAgentText(text: String) {
        val events = AgentEventParser.parseFrameText(text)
        for (e in events) applyEvent(e)
    }

    /** "Step 3 · " prefix for step rows (audit §2-M4) — without it
     *  neither the user nor anyone helping them can map the chat to
     *  the step the agent is on. */
    private fun stepPrefix(step: Int): String =
        if (step > 0) "Step $step · " else ""

    private fun applyEvent(e: AgentEvent) {
        when (e) {
            is AgentEvent.Backend -> addRow(AgentRow(
                AgentRowKind.BACKEND,
                title = "backend: ${e.mode}" +
                    (if (e.model.isNotEmpty()) " · ${e.model}" else "")))
            is AgentEvent.Plan -> addRow(AgentRow(
                AgentRowKind.THINKING,
                title = stepPrefix(e.step) + "Thinking…",
                detail = e.thought.ifEmpty { e.describe.ifEmpty { null } },
            ))
            is AgentEvent.Action -> addRow(AgentRow(
                AgentRowKind.DOING,
                title = stepPrefix(e.step) +
                    "I'm about to do this: ${e.describe}"))
            is AgentEvent.Result -> addRow(if (e.ok) AgentRow(
                AgentRowKind.RESULT_OK,
                title = stepPrefix(e.step) +
                    (if (e.excerpt.isNotEmpty()) e.excerpt else "Done"))
            else AgentRow(
                AgentRowKind.RESULT_FAIL,
                title = stepPrefix(e.step) +
                    (if (e.excerpt.isNotEmpty()) e.excerpt else "Failed")))
            is AgentEvent.ConfirmNeeded -> {
                addRow(AgentRow(
                    AgentRowKind.DOING,
                    title = stepPrefix(e.step) +
                        "Permission needed: ${e.describe}"))
                _state.update {
                    it.copy(confirm = AgentConfirm(
                        describe = e.describe, tool = e.tool, why = e.why))
                }
            }
            is AgentEvent.ConfirmExpired -> {
                // The wait timed out: drop the (now unanswerable) card
                // so a late tap cannot produce a second, contradictory
                // "nothing was waiting" message (§2-M5).
                _state.update { it.copy(confirm = null) }
                addRow(AgentRow(
                    AgentRowKind.NOTE,
                    title = stepPrefix(e.step) +
                        "Timed out waiting — nothing was done."))
            }
            is AgentEvent.Done -> {
                _state.update { it.copy(active = false, confirm = null) }
                addRow(AgentRow(
                    AgentRowKind.DONE,
                    title = "Done" +
                        (if (e.summary.isNotEmpty()) ": ${e.summary}" else "")))
            }
            is AgentEvent.Failed -> {
                _state.update { it.copy(active = false, confirm = null) }
                addRow(AgentRow(
                    AgentRowKind.FAILED,
                    title = "Stopped" +
                        (if (e.error.isNotEmpty()) ": ${humanAgentError(e.error)}" else "")))
            }
            is AgentEvent.Note -> addRow(AgentRow(AgentRowKind.NOTE, title = e.text))
        }
    }

/** Bridge/agent failures in plain words — never a raw errno string. */
    private fun humanAgentError(raw: String): String {
        val e = raw.lowercase()
        return when {
            "cannot reach" in e || "connection refused" in e || "connecterror" in e ->
                if ("127.0.0.1" in e || "localhost" in e)
                    "the local model server is not running. Start it " +
                        "(Tools \u2192 Services) and ask again."
                else
                    "the AI service could not be reached. Check the " +
                        "internet connection and try again."
            "401" in e || "unauthorized" in e || "invalid api key" in e ->
                "the AI service rejected the API key. Check it in Settings."
            "timed out" in e || "timeout" in e ->
                "the model took too long to answer. It may still be " +
                    "loading \u2014 give it a moment and ask again."
            else -> "Something unexpected happened: $raw"
        }
    }

    private fun addRow(row: AgentRow) {
        _state.update {
            // the stream is chat history, not a log file — cap it
            val rows = (it.rows + row).takeLast(MAX_ROWS)
            it.copy(rows = rows)
        }
    }

    // ---- confirm -----------------------------------------------------------

    fun allow() = answerConfirm("allow")
    fun deny() = answerConfirm("deny")

    private fun answerConfirm(decision: String) {
        val taskId = _state.value.taskId ?: return
        if (_state.value.confirmBusy) return
        _state.update { it.copy(confirmBusy = true) }
        viewModelScope.launch {
            when (val r = client().confirmAgentTask(taskId, decision)) {
                is BridgeResult.Success ->
                    _state.update {
                        it.copy(confirm = null, confirmBusy = false,
                                streamError = null)
                    }
                is BridgeResult.HttpError -> {
                    // 409 = nothing awaiting (timed out / already answered)
                    _state.update {
                        it.copy(confirm = null, confirmBusy = false,
                                streamError = if (r.status == 409)
                                    "Nothing was waiting for an answer."
                                else "The bridge answered with an error " +
                                    "(HTTP ${r.status}).")
                    }
                }
                is BridgeResult.NetworkError ->
                    _state.update {
                        it.copy(confirmBusy = false,
                                streamError = "Can't reach the bridge — it may " +
                                    "be stopped. In Termux run: ~/.trmx/trmx start")
                    }
            }
        }
    }

    // ---- stop --------------------------------------------------------------

    /** Visible Stop: cancels the agent job (SIGTERM → the agent emits a
     *  final `failed` event and exits, §5). */
    fun stop() {
        val jobId = _state.value.jobId ?: return
        viewModelScope.launch {
            when (val r = client().cancelJob(jobId)) {
                is BridgeResult.Success -> {
                    _state.update { it.copy(active = false) }
                    addRow(AgentRow(AgentRowKind.FAILED, title = "Stopped"))
                }
                is BridgeResult.HttpError ->
                    _state.update { st -> st.copy(streamError = "Stop failed: HTTP ${r.status}") }
                is BridgeResult.NetworkError ->
                    _state.update { it.copy(streamError = "Stop failed: bridge unreachable") }
            }
        }
    }

    fun clearStreamError() = _state.update { it.copy(streamError = null) }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MAX_ROWS = 500
        private val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED", "CANCELLED", "LOST")
    }
}
