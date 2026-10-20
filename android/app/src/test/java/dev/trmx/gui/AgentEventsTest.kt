package dev.trmx.gui

import dev.trmx.gui.agent.AgentEvent
import dev.trmx.gui.agent.AgentEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventsTest {

    @Test
    fun `step events keep their step number`() {
        // audit §2-M4: the chat renders "Step N" from these events —
        // the parser must hand the step through, not drop it.
        val plan = AgentEventParser.parseLine(
            """{"t":"plan","step":3,"thought":"look first","describe":"list files"}""")
        assertTrue(plan is AgentEvent.Plan)
        assertEquals(3, (plan as AgentEvent.Plan).step)

        val result = AgentEventParser.parseLine(
            """{"t":"result","step":3,"ok":true,"excerpt":"done it"}""")
        assertTrue(result is AgentEvent.Result)
        assertEquals(3, (result as AgentEvent.Result).step)
    }

    @Test
    fun `confirm-expired parses as its own event`() {
        // audit §2-M5: distinct from confirm-needed and from failed,
        // so the app can replace the stale confirm card.
        val e = AgentEventParser.parseLine(
            """{"t":"confirm-expired","step":2,"describe":"delete it","tool":"submit_tool","why":"destructive"}""")
        assertTrue(e is AgentEvent.ConfirmExpired)
        e as AgentEvent.ConfirmExpired
        assertEquals(2, e.step)
        assertEquals("delete it", e.describe)
        assertEquals("submit_tool", e.tool)
    }

    @Test
    fun `garbage still becomes a note`() {
        val e = AgentEventParser.parseLine("not json at all")
        assertTrue(e is AgentEvent.Note)
    }
}
