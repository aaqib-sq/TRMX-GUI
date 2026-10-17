package dev.trmx.gui

import dev.trmx.gui.wizard.PairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {

    private val token = "6uBQdGVzdC10b2tlbi0xMjM0NTY3ODkwYWJjZGVm"

    @Test
    fun `build and parse round-trip`() {
        val uri = PairLink.build(token)
        assertEquals("trmx://pair?token=$token", uri)
        assertEquals(token, PairLink.tokenFrom(uri))
    }

    @Test
    fun `token among other query params is found`() {
        assertEquals(token, PairLink.tokenFrom("trmx://pair?src=termux&token=$token"))
    }

    @Test
    fun `foreign or malformed links are rejected`() {
        assertNull(PairLink.tokenFrom(null))
        assertNull(PairLink.tokenFrom(""))
        assertNull(PairLink.tokenFrom("https://pair?token=$token"))
        assertNull(PairLink.tokenFrom("trmx://other?token=$token"))
        assertNull(PairLink.tokenFrom("trmx://pair"))
        assertNull(PairLink.tokenFrom("trmx://pair?other=1"))
        assertNull(PairLink.tokenFrom("trmx://pair?token=short"))
        assertNull(PairLink.tokenFrom("trmx://pair?token=has+plus/and=slash"))
        assertNull(PairLink.tokenFrom("trmx://pair?token="))
    }
}
