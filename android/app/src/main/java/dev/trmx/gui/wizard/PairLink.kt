package dev.trmx.gui.wizard

/*
 * Pair-link: the one-command connect path (2026-10-17).
 *
 * `trmx pair-link` in Termux builds a link of the form
 *     trmx://pair?token=<url-safe-base64 token>
 * carrying the BRIDGE's pairing token, and opens it at this app
 * (explicit component intent via `am`). The bridge's token is the
 * auth truth, so the app adopts it (SetupViewModel.adoptFromLink) and
 * verifies the connection with a probe — no token typing, no file
 * write, no intent sent FROM the app.
 *
 * Pure string handling (no Android imports) so the parsing rules are
 * JVM-unit-tested: anything that is not exactly our scheme with a
 * well-formed token is rejected.
 */

object PairLink {

    const val SCHEME_PREFIX = "trmx://pair"

    /** The link the Termux CLI builds (kept in sync with termux/trmx). */
    fun build(token: String): String = "$SCHEME_PREFIX?token=$token"

    /** Extracts the token from a pair-link URI, or null if the URI is
     *  not a well-formed pair-link. The token charset is url-safe
     *  base64 (the same alphabet TokenStore.generateToken produces and
     *  the bridge CLI validates). */
    fun tokenFrom(uri: String?): String? {
        if (uri == null || !uri.startsWith(SCHEME_PREFIX)) return null
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            if (part.startsWith("token=")) {
                val t = part.substring("token=".length)
                return if (isTokenShape(t)) t else null
            }
        }
        return null
    }

    private fun isTokenShape(t: String): Boolean =
        t.length >= 20 && t.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }
}
