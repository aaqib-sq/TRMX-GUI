package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thread-safe on-device storage for download history.
 * Injected with a directory (e.g. context.filesDir) to remain 100% JVM-testable.
 * Zero network, purely local JSON file persistence.
 */
class DownloadHistoryStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File get() = File(dir, "download_history.json")

    @Synchronized
    fun list(): List<DownloadRecord> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString(ListSerializer(DownloadRecord.serializer()), file.readText())
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun byId(id: String): DownloadRecord? = list().firstOrNull { it.id == id }

    @Synchronized
    fun add(record: DownloadRecord) {
        val current = list().filterNot { it.id == record.id }
        persist(listOf(record) + current)
    }

    @Synchronized
    fun update(record: DownloadRecord) {
        val current = list()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            val updated = current.toMutableList()
            updated[index] = record
            persist(updated)
        } else {
            add(record)
        }
    }

    @Synchronized
    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    /**
     * Resets a failed or cancelled record into QUEUED state ready for re-submission.
     */
    @Synchronized
    fun retry(id: String): DownloadRecord? {
        val record = byId(id) ?: return null
        val retried = record.copy(
            status = "QUEUED",
            progressPct = 0f,
            progressDetail = "Retrying…",
            error = null,
            createdAt = System.currentTimeMillis(),
            endedAt = null
        )
        update(retried)
        return retried
    }

    private fun persist(records: List<DownloadRecord>) {
        dir.mkdirs()
        val tmp = File(dir, "download_history.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(DownloadRecord.serializer()), records))
        if (!tmp.renameTo(file)) {
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
