package com.angelminga.claudecommit

/** [type] is the raw type Claude returned (may be non-standard), or empty if none could be found. */
data class CommitSuggestion(val type: String, val reason: String, val message: String)

/**
 * Parses Claude's reply (TYPE / REASON / MESSAGE format). Also accepts the Spanish labels used by older prompts.
 * Has no IntelliJ dependencies so it can be unit tested.
 */
object CommitMessageParser {

    val TYPES = listOf("feat", "fix", "refactor", "perf", "style", "docs", "test", "build", "ci", "chore", "revert")

    // Opening code fence with an optional language tag (```text, ```markdown…) and closing fence.
    private val OPENING_FENCE = Regex("""^```[\w-]*[ \t]*\r?\n?""")
    private val CLOSING_FENCE = Regex("""\r?\n?```\s*$""")

    private val TYPE_LINE = Regex("""(?im)^(?:TYPE|TIPO):\s*([a-z]+)""")
    private val REASON_LINE = Regex("""(?imu)^(?:REASON|RAZ[OÓ]N):\s*(.+)$""")
    private val MESSAGE_BLOCK = Regex("""(?is)(?:MESSAGE|MENSAJE):[ \t]*\r?\n?(.*)""")

    /**
     * The commit message part of a reply that is still being streamed: whatever follows `MESSAGE:` so far, or null if
     * that label hasn't arrived yet (the TYPE and REASON lines are not shown to the user).
     */
    fun partialMessage(raw: String): String? {
        val match = MESSAGE_BLOCK.find(raw.replace(OPENING_FENCE, "")) ?: return null
        return match.groupValues[1].replace(OPENING_FENCE, "").replace(CLOSING_FENCE, "").trimStart().trimEnd('`').trimEnd()
            .takeIf { it.isNotEmpty() }
    }

    fun parse(raw: String): CommitSuggestion {
        val text = raw.trim().replace(OPENING_FENCE, "").replace(CLOSING_FENCE, "").trim()
        val type = TYPE_LINE.find(text)?.groupValues?.get(1)?.lowercase()
        val reason = REASON_LINE.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val message = MESSAGE_BLOCK.find(text)?.groupValues?.get(1)
            ?.replace(OPENING_FENCE, "")?.replace(CLOSING_FENCE, "")?.trim()

        // If Claude ignored the format, use the whole output as the message and infer the type from the title.
        val finalMessage = message?.takeIf { it.isNotBlank() } ?: text
        val finalType = type
            ?: Regex("""^([a-z]+)(\(.*?\))?!?:""").find(finalMessage)?.groupValues?.get(1)
            ?: ""

        return CommitSuggestion(type = finalType, reason = reason, message = finalMessage)
    }
}
