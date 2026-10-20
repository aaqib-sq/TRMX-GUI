package dev.trmx.gui.store

/*
 * The last agent task's identity, persisted so an app process death does
 * not lose the conversation (Arena audit finding 6). File-backed
 * (filesDir/agent-session.json), same constructor-injected pattern as
 * RecipeStore so it is JVM-testable without Android.
 *
 * Only the task/job ids + the user's instruction are kept — the
 * transcript itself is rebuilt from the bridge: the job output stream
 * replays from seq 1, and GET /v1/agent/tasks/{id} carries the task's
 * current/final state. Nothing here is secret, so no encryption (unlike
 * TokenStore's pairing token).
 */

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AgentSession(
    val taskId: String,
    val jobId: String,
    val instruction: String,
    val startedAt: Long = 0,
)

class AgentSessionStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File get() = File(dir, "agent-session.json")

    fun load(): AgentSession? = runCatching {
        if (!file.exists()) null
        else json.decodeFromString(AgentSession.serializer(), file.readText())
    }.getOrNull()

    fun save(session: AgentSession) {
        dir.mkdirs()
        val tmp = File(dir, "agent-session.json.tmp")
        tmp.writeText(json.encodeToString(AgentSession.serializer(), session))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not persist agent-session.json" }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
