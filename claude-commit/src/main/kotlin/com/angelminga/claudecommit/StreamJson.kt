package com.angelminga.claudecommit

/**
 * Minimal reader for the CLI's `--output-format stream-json` lines (one JSON object per line). Only the two fields the
 * plugin needs are extracted, so no JSON library is required:
 * - text fragments: `{"type":"stream_event","event":{..."delta":{"type":"text_delta","text":"..."}}}`
 * - the final result: `{..."type":"result","is_error":false,"result":"..."}`
 */
object StreamJson {

    data class Result(val text: String, val isError: Boolean)

    private const val JSON_STRING = """"((?:[^"\\]|\\.)*)""""
    private val DELTA_TEXT = Regex(""""text_delta"\s*,\s*"text"\s*:\s*$JSON_STRING""")
    private val RESULT_TEXT = Regex(""""result"\s*:\s*$JSON_STRING""")
    private val RESULT_TYPE = Regex(""""type"\s*:\s*"result"""")
    private val IS_ERROR = Regex(""""is_error"\s*:\s*true""")

    /** Text fragment carried by a `text_delta` event, or null for any other line. */
    fun textDelta(line: String): String? =
        if (line.contains("\"content_block_delta\"")) DELTA_TEXT.find(line)?.groupValues?.get(1)?.let(::unescape) else null

    /** Final `result` event, or null for any other line. */
    fun result(line: String): Result? {
        if (!RESULT_TYPE.containsMatchIn(line)) return null
        val text = RESULT_TEXT.find(line)?.groupValues?.get(1)?.let(::unescape) ?: return null
        return Result(text, IS_ERROR.containsMatchIn(line))
    }

    /** Decodes the escapes of a JSON string body. */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.append(c); i++; continue
            }
            when (val e = s[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        out.append(code.toChar()); i += 6; continue
                    }
                    out.append("\\u")
                }
                else -> out.append(e) // \" \\ \/
            }
            i += 2
        }
        return out.toString()
    }
}
