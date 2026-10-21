package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stream-json lines below are trimmed copies of real Claude Code 2.1.296 output. */
class StreamingTest {

    private val delta =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"YPE: feat\nRE"}},"session_id":"f72a"}"""
    private val result =
        """{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"result":"TYPE: feat\nREASON: a\u00f1ade un \"saludo\".\nMESSAGE:\nfeat: add greeting","session_id":"x"}"""
    private val notLoggedIn =
        """{"duration_api_ms":0,"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login","session_id":"5937"}"""

    @Test
    fun `reads text fragments and ignores other events`() {
        assertEquals("YPE: feat\nRE", StreamJson.textDelta(delta))
        assertNull(StreamJson.textDelta("""{"type":"stream_event","event":{"type":"message_start"}}"""))
        assertNull(StreamJson.textDelta(result))
    }

    @Test
    fun `reads the final result with escapes`() {
        assertEquals(StreamJson.Result("TYPE: feat\nREASON: añade un \"saludo\".\nMESSAGE:\nfeat: add greeting", false), StreamJson.result(result))
        assertEquals(StreamJson.Result("Not logged in · Please run /login", true), StreamJson.result(notLoggedIn))
        assertNull(StreamJson.result(delta))
    }

    @Test
    fun `unescapes JSON strings`() {
        assertEquals("a\"b\\c/d\te\nf✳", StreamJson.unescape("""a\"b\\c\/d\te\nf\u2733"""))
    }

    @Test
    fun `stream output becomes a plain result for the parser and the error messages`() {
        val ok = ClaudeClient.fromStream(ShellResult(0, "$delta\n$result\n", ""), "ignored")
        assertEquals(0, ok.exitCode)
        assertEquals("feat: add greeting", CommitMessageParser.parse(ok.stdout).message)

        val login = ClaudeClient.fromStream(ShellResult(1, "$notLoggedIn\n", ""), "")
        assertEquals("Not logged in · Please run /login", login.stdout)
        assertEquals(ClaudeCommitBundle.message("error.not.logged.in"), ClaudeClient.errorMessage(login))

        // An error result never counts as a success, even with exit code 0.
        assertEquals(1, ClaudeClient.fromStream(ShellResult(0, "$notLoggedIn\n", ""), "").exitCode)

        // Unknown option: plain stderr, kept as is so the fast mode fallback still detects it.
        val old = ClaudeClient.fromStream(ShellResult(1, "", "error: unknown option '--include-partial-messages'"), "")
        assertEquals(true, ClaudeClient.isUnsupportedOption(old))

        // No result line (e.g. the process was killed): fall back to the accumulated fragments.
        assertEquals("TYPE: fix", ClaudeClient.fromStream(ShellResult(0, "$delta\n", ""), "TYPE: fix").stdout)
    }

    @Test
    fun `partial message shows only what follows MESSAGE`() {
        assertNull(CommitMessageParser.partialMessage("TYPE: feat\nREASON: adds"))
        assertNull(CommitMessageParser.partialMessage("TYPE: feat\nREASON: adds\nMESSAGE:\n"))
        assertEquals("feat(ui): add", CommitMessageParser.partialMessage("TYPE: feat\nREASON: x\nMESSAGE:\nfeat(ui): add"))
        assertEquals("feat: a\n\n- b", CommitMessageParser.partialMessage("```text\nTYPE: feat\nREASON: x\nMESSAGE:\nfeat: a\n\n- b\n``"))
    }

    @Test
    fun `generated, lock and minified files are omitted from the diff`() {
        assertEquals("generated or build output", DiffCollector.omittedReason("claude-commit/bin/main/com/x/ClaudeClient.kt"))
        assertEquals("generated or build output", DiffCollector.omittedReason("web/dist/app.js"))
        assertEquals("generated or build output", DiffCollector.omittedReason("node_modules/x/index.js"))
        assertEquals("lock file", DiffCollector.omittedReason("frontend/package-lock.json"))
        assertEquals("lock file", DiffCollector.omittedReason("go.sum"))
        assertEquals("minified file or source map", DiffCollector.omittedReason("static/app.min.js"))
        assertEquals("minified file or source map", DiffCollector.omittedReason("static/app.js.map"))
    }

    @Test
    fun `source files are kept, including packages named like build folders`() {
        assertNull(DiffCollector.omittedReason("claude-commit/src/main/kotlin/com/angelminga/claudecommit/ClaudeClient.kt"))
        assertNull(DiffCollector.omittedReason("src/main/java/com/acme/build/Builder.java"))
        assertNull(DiffCollector.omittedReason("build.gradle.kts"))
        assertNull(DiffCollector.omittedReason("docs/output.md"))
        assertNull(DiffCollector.omittedReason("C:\\repo\\src\\out\\Main.kt"))
    }
}
