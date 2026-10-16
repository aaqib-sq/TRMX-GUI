package dev.trmx.gui.agent

/*
 * Agent event model (revival §5): the agent job's stdout is JSONL — one
 * event object per line. This file turns raw lines into typed events the
 * AgentScreen renders as plain step rows. Pure Kotlin, no Android
 * dependencies, so it stays unit-testable.
 *
 * Events (§5):
 *   backend        {"t":"backend","mode":"cli|http_api","model":"..."}
 *   plan           {"t":"plan","step":n,"thought":"...","describe":"..."}
 *   action         {"t":"action","step":n,"tool":"submit_tool","describe":"..."}
 *   result         {"t":"result","step":n,"ok":true|false,"excerpt":"..."}
 *   confirm-needed {"t":"confirm-needed","step":n,"describe":"...","tool":"...","why":"..."}
 *   done           {"t":"done","summary":"..."}
 *   failed         {"t":"failed","error":"..."}
 */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface AgentEvent {
    data class Backend(val mode: String, val model: String) : AgentEvent
    data class Plan(val step: Int, val thought: String, val describe: String) : AgentEvent
    data class Action(val step: Int, val tool: String, val describe: String) : AgentEvent
    data class Result(val step: Int, val ok: Boolean, val excerpt: String) : AgentEvent
    data class ConfirmNeeded(
        val step: Int,
        val describe: String,
        val tool: String,
        val why: String,
    ) : AgentEvent
    data class Done(val summary: String) : AgentEvent
    data class Failed(val error: String) : AgentEvent
    /** A stdout line that is not a JSON event (log noise, tracebacks). */
    data class Note(val text: String) : AgentEvent
}

object AgentEventParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse one stdout line; never throws — garbage becomes a Note. */
    fun parseLine(line: String): AgentEvent {
        val el = runCatching { json.parseToJsonElement(line).jsonObject }
            .getOrNull() ?: return AgentEvent.Note(line)
        fun s(k: String): String = el[k]?.jsonPrimitive?.contentOrNull ?: ""
        fun step(): Int = el["step"]?.jsonPrimitive?.intOrNull ?: 0
        return when (el["t"]?.jsonPrimitive?.contentOrNull) {
            "backend" -> AgentEvent.Backend(mode = s("mode"), model = s("model"))
            "plan" -> AgentEvent.Plan(
                step = step(), thought = s("thought"), describe = s("describe"))
            "action" -> AgentEvent.Action(
                step = step(), tool = s("tool"), describe = s("describe"))
            "result" -> AgentEvent.Result(
                step = step(),
                ok = el["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                excerpt = s("excerpt"))
            "confirm-needed" -> AgentEvent.ConfirmNeeded(
                step = step(), describe = s("describe"),
                tool = s("tool"), why = s("why"))
            "done" -> AgentEvent.Done(summary = s("summary"))
            "failed" -> AgentEvent.Failed(error = s("error"))
            else -> AgentEvent.Note(line)
        }
    }

    /**
     * A single SSE stdout frame's data JSON is
     * {"job_id":..,"seq":..,"text":".."} — split the text into lines and
     * parse each as an event.
     */
    fun parseFrameText(text: String): List<AgentEvent> =
        text.split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .map { parseLine(it) }
}
