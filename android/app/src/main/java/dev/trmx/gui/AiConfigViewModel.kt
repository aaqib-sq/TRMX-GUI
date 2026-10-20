package dev.trmx.gui

/*
 * AiConfigViewModel — the AI backend config domain (protocol 1.1, §15),
 * split out of AppViewModel (Batch 3 structural, audit §6). Code moved
 * verbatim; this domain touched no other domain's state, so the move
 * needed no adaptation. Like AgentViewModel / SetupViewModel, it
 * builds its own TokenStore/BridgeClient per call.
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.model.AiConfig
import dev.trmx.gui.model.AiConfigUpdate
import dev.trmx.gui.model.AiCliUpdate
import dev.trmx.gui.model.AiHttpUpdate
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.store.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** AI backend config form state (protocol 1.1, §15). */
data class AiConfigState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
    val exists: Boolean = false,
    val mode: String = "cli",           // cli | http_api
    val provider: String = "groq",
    val endpoint: String = "",
    val model: String = "",
    val apiKeySet: Boolean = false,
    val apiKeyInput: String = "",       // blank = keep the saved key
    val clearKey: Boolean = false,      // explicit "forget saved key"
    val command: String = "ollama run llama3.2",
    val error: String? = null,
    val notice: String? = null,
    // Monotonic count of successful saves. The config editor watches it
    // and closes on success (device feedback, 2026-10-20).
    val saveCount: Int = 0,
)


class AiConfigViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TokenStore(app)
    private val http = OkHttpClient()

    private val _aiConfig = MutableStateFlow(AiConfigState())
    val aiConfig = _aiConfig.asStateFlow()

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

    // ---- AI backend config (protocol 1.1, §15) --------------------------------

    fun loadAiConfig() {
        if (_aiConfig.value.loading || _aiConfig.value.saving) return
        _aiConfig.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = client().getAiConfig()) {
                is BridgeResult.Success -> _aiConfig.update {
                    aiConfigFrom(r.data, it.copy(loading = false, loaded = true))
                }
                is BridgeResult.HttpError -> _aiConfig.update {
                    it.copy(loading = false, loaded = true, error = aiCfgError(r))
                }
                is BridgeResult.NetworkError -> _aiConfig.update {
                    it.copy(loading = false, loaded = true,
                            error = "bridge unreachable: ${r.cause.message}")
                }
            }
        }
    }

    private fun aiCfgError(r: BridgeResult.HttpError): String =
        if (r.status == 404)
            "AI configuration needs bridge v0.5.1+ — update the backend first"
        else "HTTP ${r.status} ${r.code} — ${r.message}"

    /** Rebuild the form fields from a (masked) server view. */
    private fun aiConfigFrom(c: AiConfig, base: AiConfigState): AiConfigState = base.copy(
        exists = c.exists,
        mode = c.mode,
        provider = c.http_api?.provider ?: "groq",
        endpoint = c.http_api?.endpoint ?: "",
        model = c.http_api?.model ?: "",
        apiKeySet = c.http_api?.api_key_set ?: false,
        apiKeyInput = "",
        clearKey = false,
        command = (c.cli?.command ?: emptyList()).joinToString(" "),
    )

    fun setAiMode(mode: String) = _aiConfig.update { it.copy(mode = mode, error = null) }
    fun setAiProvider(p: String) = _aiConfig.update { it.copy(provider = p, error = null) }
    fun setAiEndpoint(v: String) = _aiConfig.update { it.copy(endpoint = v) }
    fun setAiModel(v: String) = _aiConfig.update { it.copy(model = v) }
    fun setAiApiKey(v: String) =
        _aiConfig.update { it.copy(apiKeyInput = v, clearKey = false) }
    fun setAiCommand(v: String) = _aiConfig.update { it.copy(command = v) }
    fun forgetAiKey() = _aiConfig.update { it.copy(clearKey = true, apiKeyInput = "") }
    fun clearAiNotice() = _aiConfig.update { it.copy(notice = null, error = null) }

    /**
     * Redesign v2: one-tap LOCAL GGUF preset. The bridge talks to a
     * llama.cpp server (OpenAI-compatible) running on this phone — the
     * SAME .gguf file the owner already has (PocketPal/ChatterUI use it
     * read-only from shared storage), no copy, no internet, no API money.
     * A dummy key is stored because openai_compatible expects one;
     * llama-server ignores it.
     */
    fun applyLocalGgufPreset() {
        _aiConfig.update {
            it.copy(mode = "http_api", provider = "openai_compatible",
                endpoint = "http://127.0.0.1:8080/v1/chat/completions",
                model = "local-gguf", apiKeyInput = "local",
                clearKey = false, error = null)
        }
        saveAiConfig()
    }

    fun saveAiConfig() {
        val s0 = _aiConfig.value
        if (s0.saving) return
        val cmd = s0.command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (s0.mode == "cli" && cmd.isEmpty()) {
            _aiConfig.update { it.copy(error = "offline mode needs a command, e.g. ollama run llama3.2") }
            return
        }
        if (s0.mode == "http_api" && s0.model.isBlank()) {
            _aiConfig.update { it.copy(error = "cloud mode needs a model") }
            return
        }
        _aiConfig.update { it.copy(saving = true, error = null, notice = null) }
        val request = AiConfigUpdate(
            mode = s0.mode,
            cli = AiCliUpdate(command = cmd),
            http_api = AiHttpUpdate(
                provider = s0.provider,
                endpoint = s0.endpoint.trim(),
                model = s0.model.trim(),
                api_key = when {
                    s0.clearKey -> ""                       // clear
                    s0.apiKeyInput.isNotBlank() -> s0.apiKeyInput.trim()
                    else -> null                            // keep
                },
            ),
        )
        viewModelScope.launch {
            when (val r = client().updateAiConfig(request)) {
                is BridgeResult.Success -> {
                    _aiConfig.update {
                        aiConfigFrom(r.data, it.copy(saving = false,
                                                     notice = "backend saved",
                                                     saveCount = it.saveCount + 1))
                    }
                }
                is BridgeResult.HttpError ->
                    _aiConfig.update { it.copy(saving = false, error = aiCfgError(r)) }
                is BridgeResult.NetworkError ->
                    _aiConfig.update {
                        it.copy(saving = false,
                                error = "bridge unreachable: ${r.cause.message}")
                    }
            }
        }
    }
}
