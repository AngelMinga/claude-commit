package com.angelminga.claudecommit

data class CommitSuggestion(val type: String, val reason: String, val message: String)

/** Interpreta la respuesta de Claude (formato TIPO / RAZON / MENSAJE). Sin dependencias de IntelliJ para poder testearla. */
object CommitMessageParser {

    val TYPES = listOf("feat", "fix", "refactor", "perf", "style", "docs", "test", "build", "ci", "chore", "revert")

    // Valla de código inicial con etiqueta opcional (```text, ```markdown…) y valla final.
    private val OPENING_FENCE = Regex("""^```[\w-]*[ \t]*\r?\n?""")
    private val CLOSING_FENCE = Regex("""\r?\n?```\s*$""")

    fun parse(raw: String): CommitSuggestion {
        val text = raw.trim().replace(OPENING_FENCE, "").replace(CLOSING_FENCE, "").trim()
        val type = Regex("""(?im)^TIPO:\s*([a-z]+)""").find(text)?.groupValues?.get(1)?.lowercase()
        val reason = Regex("""(?imu)^RAZ[OÓ]N:\s*(.+)$""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val message = Regex("""(?is)MENSAJE:[ \t]*\r?\n?(.*)""").find(text)?.groupValues?.get(1)
            ?.replace(OPENING_FENCE, "")?.replace(CLOSING_FENCE, "")?.trim()

        // Si Claude no respetó el formato, usamos todo como mensaje y deducimos el tipo del título.
        val finalMessage = message?.takeIf { it.isNotBlank() } ?: text
        val finalType = type
            ?: Regex("""^([a-z]+)(\(.*?\))?!?:""").find(finalMessage)?.groupValues?.get(1)
            ?: "desconocido"

        return CommitSuggestion(
            type = if (finalType in TYPES) finalType else "$finalType (no estándar)",
            reason = reason,
            message = finalMessage,
        )
    }
}
