package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Custom `claude` path, custom model and `--effort`. */
class CliOptionsTest {

    @Test
    fun `unix scripts pass effort and custom path through environment variables`() {
        for (inv in listOf(
            ClaudeClient.unixInvocation("/bin/zsh", "P", "D", "opus", effort = "low", claudePath = "/opt/claude"),
            ClaudeClient.unixFastInvocation("/bin/zsh", "S", "U", "opus", effort = "low", claudePath = "/opt/claude"),
        )) {
            val script = inv.command[3]
            assertTrue(script, script.contains("set -- \"\$@\" --effort \"\$CLAUDE_COMMIT_EFFORT\"; "))
            assertTrue(script, script.contains("\"\$CLAUDE_COMMIT_BIN\" -p "))
            assertFalse("values must never be interpolated: $script", script.contains("/opt/claude") || script.contains(" low"))
            assertEquals("low", inv.env["CLAUDE_COMMIT_EFFORT"])
            assertEquals("/opt/claude", inv.env["CLAUDE_COMMIT_BIN"])
        }
    }

    @Test
    fun `without effort or path the unix scripts are unchanged`() {
        val inv = ClaudeClient.unixInvocation("/bin/zsh", "P", "D", "")
        assertFalse(inv.command[3].contains("--effort"))
        assertTrue(inv.command[3].contains("; claude -p "))
        assertEquals(setOf("CLAUDE_COMMIT_PROMPT", "CLAUDE_COMMIT_MODEL"), inv.env.keys)
    }

    @Test
    fun `the shell really passes model and effort to the configured executable`() {
        val dir = Files.createTempDirectory("claude-commit-test").toFile()
        val fake = File(dir, "fake claude").apply {
            writeText("#!/bin/sh\nfor a in \"\$@\"; do echo \"[\$a]\"; done\n")
            setExecutable(true)
        }
        try {
            val inv = ClaudeClient.unixInvocation("/bin/sh", "the prompt", "", "sonnet", effort = "high", claudePath = fake.path)
            val res = Shell.run(inv.command, dir, inv.stdin, inv.env, timeoutSec = 10)
            assertEquals(
                listOf("[-p]", "[the prompt]", "[--tools]", "[]", "[--no-session-persistence]", "[--model]", "[sonnet]", "[--effort]", "[high]"),
                res.stdout.lines().filter { it.isNotEmpty() },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `windows arguments include effort`() {
        assertEquals(
            listOf("-p", "--tools=", "--no-session-persistence", "--model", "opus", "--effort", "max"),
            ClaudeClient.claudeArgs("opus", "max"),
        )
        val fast = ClaudeClient.windowsFastInvocation("claude.exe", "sp.txt", "U", "", effort = "low")
        assertTrue(fast.command.containsAll(listOf("--effort", "low")))
        assertFalse(fast.command.contains("--model"))
    }

    @Test
    fun `home directory is expanded in the configured path`() {
        assertEquals("/Users/ana/.local/bin/claude", ClaudeClient.expandHome("~/.local/bin/claude", "/Users/ana"))
        assertEquals("/usr/local/bin/claude", ClaudeClient.expandHome("/usr/local/bin/claude", "/Users/ana"))
    }

    @Test
    fun `custom model overrides the list`() {
        fun model(list: String?, custom: String?) = ClaudeCommitSettings().apply {
            loadState(ClaudeCommitSettings.Settings().also { it.model = list; it.customModel = custom })
        }.model
        assertEquals("claude-sonnet-5-5", model("haiku", "  claude-sonnet-5-5 "))
        assertEquals("haiku", model("haiku", "  "))
        assertEquals("", model("", ""))
    }

    @Test
    fun `unsupported option names the option`() {
        val msg = ClaudeClient.errorMessage(ShellResult(1, "", "error: unknown option '--effort'"))!!
        assertTrue(msg, msg.contains("--effort"))
        assertTrue(msg, msg.startsWith("Your Claude Code version does not support"))
    }
}
