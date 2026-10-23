package dev.trmx.gui

/*
 * FilesViewModel — the file-browser domain, split out of AppViewModel
 * (Batch 3 structural, audit §6): browsing, file ops, transfers, the
 * staged open/share slot (ADR-008) and recipe sharing (which stages its
 * JSON through that same slot). Code moved verbatim from AppViewModel;
 * the only adaptation is shareRecipe reporting its failure through a
 * caller-supplied callback — it used to write the toolbox state
 * directly; AppRoot wires the callback to AppViewModel.reportToolError.
 * Like AgentViewModel / SetupViewModel, it builds its own
 * TokenStore/BridgeClient per call (the token is read live from the
 * encrypted store, so instances cannot diverge).
 */

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.files.FileMime
import dev.trmx.gui.model.FileEntry
import dev.trmx.gui.model.FileOpRequest
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.store.Recipe
import dev.trmx.gui.store.RecipeStore
import dev.trmx.gui.store.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

data class FileBrowserState(
    val path: String = "~",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val opPending: Boolean = false,
    val transfer: Transfer? = null,
    val pendingOpen: PendingOpen? = null,
    /** Next page offset from the bridge's paged listing (§6), or null
     *  when this folder fit in one page — see loadMoreFiles(). */
    val nextOffset: Int? = null,
    val loadingMore: Boolean = false,
)

/** One in-flight file transfer (download / upload / open / share). */
data class Transfer(
    val label: String,        // "Downloading" | "Uploading" | "Opening" | "Sharing"
    val name: String,
    val bytes: Long,
    val total: Long?,         // null → indeterminate progress
)

/**
 * A bridge file staged in cache/shared, ready for the UI to fire
 * ACTION_VIEW / ACTION_SEND through the FileProvider (ADR-008).
 */
data class PendingOpen(
    val path: String,         // absolute local path
    val mime: String,
    val share: Boolean,
)


class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TokenStore(app)
    private val http = OkHttpClient()

    private val _files = MutableStateFlow(FileBrowserState())
    val files = _files.asStateFlow()

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

    /** The files line of the old AppViewModel.resetAppState, as this
     *  domain's own reset — AppRoot's repair paths call both, as before. */
    fun resetFiles() {
        _files.value = FileBrowserState()
    }

    // ---- file browser (Phase 7) ------------------------------------------

    fun openPath(path: String) {
        _files.update { it.copy(path = path, loading = true, error = null, notice = null,
                                nextOffset = null, loadingMore = false) }
        viewModelScope.launch {
            when (val r = client().listFiles(path)) {
                is BridgeResult.Success ->
                    _files.update { it.copy(entries = r.data.entries, loading = false,
                                            nextOffset = r.data.next_offset) }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(loading = false, error = "HTTP ${r.status}: ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(loading = false, error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    fun refreshFiles() = openPath(_files.value.path)

    /**
     * Append the next page of the current folder (Arena finding 17): the
     * bridge's §6 listing is paged (offset + next_offset), but the screen
     * used to stop at the first page, silently truncating folders with
     * more than a page of entries. Appends to [FileBrowserState.entries];
     * a failure keeps the loaded entries and surfaces the error.
     */
    fun loadMoreFiles() {
        val s = _files.value
        val offset = s.nextOffset ?: return
        if (s.loading || s.loadingMore) return
        _files.update { it.copy(loadingMore = true, error = null) }
        viewModelScope.launch {
            when (val r = client().listFiles(s.path, offset = offset)) {
                is BridgeResult.Success ->
                    _files.update {
                        // another openPath may have won the race — only
                        // append while we are still on the same folder
                        if (it.path != s.path) it.copy(loadingMore = false)
                        else it.copy(entries = it.entries + r.data.entries,
                                     loadingMore = false,
                                     nextOffset = r.data.next_offset)
                    }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(loadingMore = false,
                        error = "HTTP ${r.status}: ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(loadingMore = false,
                        error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    /** @return true when we moved up, false at the root (caller may pop). */
    fun filesUp(): Boolean {
        val cur = _files.value.path
        if (cur == "~" || cur == "/" || cur.isBlank()) return false
        if (cur.startsWith("/")) {
            val parent = cur.removeSuffix("/").substringBeforeLast('/')
            openPath(if (parent.isEmpty()) "/" else parent)
            return true
        }
        val parent = cur.removeSuffix("/").substringBeforeLast('/')
        openPath(if (parent.isEmpty() || parent == "~") "~" else parent)
        return true
    }

    fun makeDir(name: String) {
        val base = _files.value.path.trimEnd('/')
        runOp(FileOpRequest(op = "mkdir", path = "$base/$name"))
    }

    fun renameEntry(entry: FileEntry, newName: String) {
        val base = _files.value.path.trimEnd('/')
        runOp(FileOpRequest(op = "rename", path = "$base/${entry.name}", new_name = newName))
    }

    fun deleteEntry(entry: FileEntry) {
        val base = _files.value.path.trimEnd('/')
        val req = if (entry.type == "dir") {
            FileOpRequest(op = "delete", path = "$base/${entry.name}",
                          recursive = true, confirm = true)
        } else {
            FileOpRequest(op = "delete", path = "$base/${entry.name}")
        }
        runOp(req)
    }

    private fun runOp(req: FileOpRequest) {
        _files.update { it.copy(opPending = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val r = client().fileOp(req)) {
                is BridgeResult.Success -> {
                    _files.update { it.copy(opPending = false, notice = "${req.op}: ok") }
                    refreshFiles()
                }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(opPending = false, error = "${req.op} failed: HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(opPending = false, error = "bridge unreachable: ${r.cause.message}") }
            }
        }
    }

    /** Download into the app's external files dir (no permissions needed). */
    fun downloadEntry(entry: FileEntry) {
        val base = _files.value.path.trimEnd('/')
        val remote = "$base/${entry.name}"
        val progress = TransferPoster("Downloading", entry.name)
        _files.update {
            it.copy(opPending = true, notice = null, error = null,
                    transfer = Transfer("Downloading", entry.name, 0, null))
        }
        viewModelScope.launch {
            val destDir = getApplication<Application>()
                .getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: getApplication<Application>().filesDir
            val dest = File(destDir, entry.name)
            when (val r = client().downloadFile(remote, dest, progress::onBytes)) {
                is BridgeResult.Success ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            notice = "saved ${r.data} bytes → ${dest.absolutePath}") }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "download failed: HTTP ${r.status} ${r.code}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "download failed: ${r.cause.message}") }
            }
        }
    }

    /**
     * Phase 8 download-then-open (ADR-008): stream the entry into the
     * single-slot cache/shared dir (cleared first, so the cache stays
     * bounded), then the UI fires ACTION_VIEW / ACTION_SEND through the
     * FileProvider.
     */
    fun openEntry(entry: FileEntry, share: Boolean) {
        if (entry.type == "dir") return
        val base = _files.value.path.trimEnd('/')
        openRemote("$base/${entry.name}", entry.name, share)
    }

    /** Path-based open/share (files browser AND artifact cards use this). */
    fun openRemote(remote: String, name: String, share: Boolean) {
        val label = if (share) "Sharing" else "Opening"
        val progress = TransferPoster(label, name)
        _files.update {
            it.copy(opPending = true, notice = null, error = null,
                    transfer = Transfer(label, name, 0, null))
        }
        viewModelScope.launch {
            val sharedDir = File(getApplication<Application>().cacheDir, "shared")
            sharedDir.mkdirs()
            sharedDir.listFiles()?.forEach { it.delete() }   // single slot
            val dest = File(sharedDir, remote.substringAfterLast('/'))
            when (val r = client().downloadFile(remote, dest, progress::onBytes)) {
                is BridgeResult.Success ->
                    _files.update {
                        it.copy(opPending = false, transfer = null,
                                pendingOpen = PendingOpen(
                                    dest.absolutePath, FileMime.of(name), share))
                    }
                is BridgeResult.HttpError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "${label.lowercase()} failed: " +
                                                "HTTP ${r.status} ${r.code} — ${r.message}") }
                is BridgeResult.NetworkError ->
                    _files.update { it.copy(opPending = false, transfer = null,
                                            error = "${label.lowercase()} failed: ${r.cause.message}") }
            }
        }
    }

    /** UI fired the open/share intent (or it failed) — clear the slot. */
    fun afterOpen(error: String?) {
        _files.update { it.copy(pendingOpen = null, error = error) }
    }

    /** Upload a picked document into the current directory. */
    fun uploadFromUri(uri: Uri, displayName: String) {
        val base = _files.value.path.trimEnd('/')
        val dest = "$base/$displayName"
        val progress = TransferPoster("Uploading", displayName)
        _files.update {
            it.copy(opPending = true, notice = null, error = null,
                    transfer = Transfer("Uploading", displayName, 0, null))
        }
        viewModelScope.launch {
            val tmp = File(getApplication<Application>().cacheDir, "upload-${System.currentTimeMillis()}")
            try {
                val resolver = getApplication<Application>().contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.IOException("cannot open picked document")
                val total = tmp.length()
                when (val r = client().uploadFile(dest, tmp) { n -> progress.onBytes(n, total) }) {
                    is BridgeResult.Success -> {
                        _files.update { it.copy(opPending = false, transfer = null,
                                                notice = "uploaded $displayName") }
                        refreshFiles()
                    }
                    is BridgeResult.HttpError ->
                        _files.update { it.copy(opPending = false, transfer = null,
                                                error = "upload failed: HTTP ${r.status} ${r.code} — ${r.message}") }
                    is BridgeResult.NetworkError ->
                        _files.update { it.copy(opPending = false, transfer = null,
                                                error = "upload failed: ${r.cause.message}") }
                }
            } catch (e: Exception) {
                _files.update { it.copy(opPending = false, transfer = null,
                                        error = "upload failed: ${e.message}") }
            } finally {
                tmp.delete()
            }
        }
    }

    fun clearFilesNotice() = _files.update { it.copy(notice = null, error = null) }

    /** Throttles progress callbacks to ~10 Hz (plus the final value). */
    private inner class TransferPoster(private val label: String, private val name: String) {
        private var last = 0L

        fun onBytes(bytes: Long, total: Long?) {
            val now = android.os.SystemClock.elapsedRealtime()
            val done = total != null && bytes >= total
            if (done || now - last >= 100) {
                last = now
                _files.update { it.copy(transfer = Transfer(label, name, bytes, total)) }
            }
        }
    }


    // ---- recipes -------------------------------------------------------------

    /** Share a recipe as JSON via the Phase 8 staging mechanism. */
    fun shareRecipe(r: Recipe, onError: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val sharedDir = File(getApplication<Application>().cacheDir, "shared")
                sharedDir.mkdirs()
                sharedDir.listFiles()?.forEach { it.delete() }   // single slot
                val safe = r.title.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val f = File(sharedDir, "trmx-recipe-$safe.json")
                f.writeText(RecipeStore.export(r))
                _files.update { it.copy(pendingOpen =
                    PendingOpen(f.absolutePath, "application/json", true)) }
            }.onFailure { e ->
                onError("share failed: ${e.message}")
            }
        }
    }
}
