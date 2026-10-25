package dev.trmx.gui.download

/*
 * Pure Kotlin HTML entity decoder.
 * Runs in both JVM unit tests and Android runtime without depending on
 * android.text.Html (which is an empty stub in unit tests).
 *
 * Decodes:
 * - Hexadecimal numeric entities: &#x909;, &#x27;, &#x1F600;
 * - Decimal numeric entities: &#39;, &#34;, &#160;
 * - Standard HTML named entities: &quot;, &amp;, &apos;, &lt;, &gt;, &nbsp;, etc.
 */

object HtmlDecoder {

    private val NAMED_ENTITIES = mapOf(
        "quot" to "\"",
        "amp" to "&",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "nbsp" to " ",
        "iexcl" to "¡",
        "cent" to "¢",
        "pound" to "£",
        "curren" to "¤",
        "yen" to "¥",
        "brvbar" to "¦",
        "sect" to "§",
        "uml" to "¨",
        "copy" to "©",
        "ordf" to "ª",
        "laquo" to "«",
        "not" to "¬",
        "shy" to "\u00AD",
        "reg" to "®",
        "macr" to "¯",
        "deg" to "°",
        "plusmn" to "±",
        "sup2" to "²",
        "sup3" to "³",
        "acute" to "´",
        "micro" to "µ",
        "para" to "¶",
        "middot" to "·",
        "cedil" to "¸",
        "sup1" to "¹",
        "ordm" to "º",
        "raquo" to "»",
        "frac14" to "¼",
        "frac12" to "½",
        "frac34" to "¾",
        "iquest" to "¿",
        "times" to "×",
        "divide" to "÷",
        "ndash" to "–",
        "mdash" to "—",
        "lsquo" to "‘",
        "rsquo" to "’",
        "sbquo" to "‚",
        "ldquo" to "“",
        "rdquo" to "”",
        "bdquo" to "„",
        "dagger" to "†",
        "Dagger" to "‡",
        "bull" to "•",
        "hellip" to "…",
        "permil" to "‰",
        "prime" to "′",
        "Prime" to "″",
        "lsaquo" to "‹",
        "rsaquo" to "›",
        "oline" to "‾",
        "frasl" to "⁄",
        "euro" to "€",
        "trade" to "™"
    )

    private val ENTITY_REGEX = Regex("&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|[a-zA-Z0-9]+);")

    /**
     * Decodes HTML entities (e.g., &#x909;, &#39;, &amp;) into their real characters.
     * Safely handles null, empty, and strings without entities.
     */
    fun decode(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        if (!input.contains('&')) return input

        return ENTITY_REGEX.replace(input) { matchResult ->
            val entity = matchResult.groupValues[1]
            when {
                entity.startsWith("#x", ignoreCase = true) -> {
                    val hex = entity.substring(2)
                    runCatching {
                        val codePoint = hex.toInt(16)
                        String(Character.toChars(codePoint))
                    }.getOrDefault(matchResult.value)
                }
                entity.startsWith("#") -> {
                    val dec = entity.substring(1)
                    runCatching {
                        val codePoint = dec.toInt(10)
                        String(Character.toChars(codePoint))
                    }.getOrDefault(matchResult.value)
                }
                else -> {
                    NAMED_ENTITIES[entity] ?: matchResult.value
                }
            }
        }
    }
}
