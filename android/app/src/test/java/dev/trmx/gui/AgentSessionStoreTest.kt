package dev.trmx.gui

import dev.trmx.gui.store.AgentSession
import dev.trmx.gui.store.AgentSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** Arena finding 6: the persisted agent session survives process death. */
class AgentSessionStoreTest {

    @Test
    fun `save load clear persist across instances`() {
        val dir = Files.createTempDirectory("trmx-agent-session").toFile()
        assertNull(AgentSessionStore(dir).load())

        val session = AgentSession("T-1", "J-1", "download the lecture", 123L)
        AgentSessionStore(dir).save(session)
        // fresh instance = from disk, like a relaunched app process
        assertEquals(session, AgentSessionStore(dir).load())

        AgentSessionStore(dir).clear()
        assertNull(AgentSessionStore(dir).load())
    }

    @Test
    fun `save replaces the previous session`() {
        val dir = Files.createTempDirectory("trmx-agent-session").toFile()
        AgentSessionStore(dir).save(AgentSession("T-1", "J-1", "first", 1L))
        AgentSessionStore(dir).save(AgentSession("T-2", "J-2", "second", 2L))
        assertEquals("T-2", AgentSessionStore(dir).load()!!.taskId)
    }

    @Test
    fun `malformed file loads as no session instead of throwing`() {
        val dir = Files.createTempDirectory("trmx-agent-session").toFile()
        dir.resolve("agent-session.json").writeText("not json at all")
        assertNull(AgentSessionStore(dir).load())
    }
}
