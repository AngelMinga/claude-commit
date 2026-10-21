package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientFastModeTest {

    @Test
    fun `unix fast command passes the rules as system prompt and the data through stdin`() {
        val inv = ClaudeClient.unixFastInvocation("/bin/zsh", "RULES", "DATA", "sonnet")
        assertEquals(listOf("/bin/zsh", "-l", "-c"), inv.command.take(3))
        val script = inv.command[3]
        assertTrue(script, script.endsWith(
            "claude -p \"\$@\" --system-prompt \"\$CLAUDE_COMMIT_SYSTEM_PROMPT\" --tools \"\" --strict-mcp-config --no-session-persistence"
        ))
        assertTrue(script.contains("set -- --model \"\$CLAUDE_COMMIT_MODEL\""))
        assertFalse(script.contains("--bare"))
        assertFalse(script.contains("--setting-sources"))
        assertFalse(script.contains("CLAUDE_COMMIT_PROMPT"))
        assertEquals("DATA", inv.stdin)
        assertEquals(mapOf("CLAUDE_COMMIT_SYSTEM_PROMPT" to "RULES", "CLAUDE_COMMIT_MODEL" to "sonnet"), inv.env)
    }

    @Test
    fun `windows fast command reads the system prompt from a file`() {
        val inv = ClaudeClient.windowsFastInvocation("C:\\Users\\Ana Pérez\\npm\\claude.cmd", "C:\\Temp\\sp.txt", "DATA", "haiku")
        assertEquals(
            listOf(
                "cmd.exe", "/c", "C:\\Users\\Ana Pérez\\npm\\claude.cmd", "-p", "--model", "haiku",
                "--system-prompt-file", "C:\\Temp\\sp.txt", "--tools=", "--strict-mcp-config", "--no-session-persistence",
            ),
            inv.command,
        )
        assertEquals("DATA", inv.stdin)
        assertFalse(inv.command.any { it.isEmpty() || it.contains('"') || it == "--bare" })
    }

    @Test
    fun `no model argument when using the CLI default`() {
        val inv = ClaudeClient.windowsFastInvocation("claude.exe", "sp.txt", "D", "")
        assertFalse(inv.command.contains("--model"))
    }

    @Test
    fun `system prompt has the same rules and no data`() {
        val system = ClaudeClient.systemPrompt(
            "Spanish", hasRecentCommits = true, alwaysIncludeScope = true, extraInstructions = "Explain why.",
            isRetry = true, projectCommitRules = "## Commits\nUse gitmoji.",
        )
        for (rule in listOf(
            "Valid types:", "- feat: a new feature for the user", "include it in the title", "in Spanish",
            "Match the style of the latest commit titles", "Always include a scope", "Explain why.",
            "rejected the previous suggestion", "Commit conventions from the project's CLAUDE.md", "Use gitmoji.",
            "Reply EXACTLY in this format", "<type>(<scope>):",
        )) assertTrue("missing: $rule", system.contains(rule))
        assertFalse(system.contains("Current branch:"))
        assertFalse(system.contains("Diff:"))
    }

    @Test
    fun `optional sections are omitted`() {
        val system = ClaudeClient.systemPrompt("English")
        for (absent in listOf("Match the style", "Always include a scope", "Additional instructions", "rejected the previous suggestion", "CLAUDE.md")) {
            assertFalse("unexpected: $absent", system.contains(absent))
        }
        assertTrue(system.contains("<type>(<optional scope>):"))
    }

    @Test
    fun `user message carries branch, titles, rejected suggestion and diff in that order`() {
        val user = ClaudeClient.userMessage("feature/ABC-1", listOf("feat: one", "fix: two"), "fix: old", "DIFF")
        assertEquals(
            "Current branch: feature/ABC-1\n\nTitles of the latest commits, newest first:\n- feat: one\n- fix: two\n\n" +
                "Rejected previous suggestion:\nfix: old\n\nDiff:\nDIFF",
            user,
        )
        assertEquals("Current branch: main\n\nDiff:\nD", ClaudeClient.userMessage("main", emptyList(), null, "D"))
    }

    @Test
    fun `detects an unsupported option and nothing else`() {
        assertTrue(ClaudeClient.isUnsupportedOption(ShellResult(1, "", "error: unknown option '--strict-mcp-config'")))
        assertFalse(ClaudeClient.isUnsupportedOption(ShellResult(1, "Not logged in · Please run /login", "")))
        assertFalse(ClaudeClient.isUnsupportedOption(ShellResult(0, "fix: handle unknown option in parser", "")))
    }

    @Test
    fun `extracts only the commit section of CLAUDE md`() {
        val md = """
            # Project

            ## Build
            Run gradle.

            ## Commit messages
            Use English.

            ```text
            # not a heading inside a fence
            ```

            ### Scopes
            Use module names.

            ## Architecture
            Ignored.
        """.trimIndent()
        assertEquals(
            "## Commit messages\nUse English.\n\n```text\n# not a heading inside a fence\n```\n\n### Scopes\nUse module names.",
            ClaudeClient.commitSection(md),
        )
    }

    @Test
    fun `no commit section returns null and long sections are capped`() {
        assertNull(ClaudeClient.commitSection("# Project\n\n## Build\nRun gradle."))
        val long = ClaudeClient.commitSection("## Committing\n" + "x".repeat(10_000), maxChars = 100)!!
        assertEquals(101, long.length)
    }
}
