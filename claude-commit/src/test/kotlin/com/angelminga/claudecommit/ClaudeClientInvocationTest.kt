package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientInvocationTest {

    @Test
    fun `unix runs claude through a login shell with prompt and model in the environment`() {
        val inv = ClaudeClient.unixInvocation("/bin/zsh", "PROMPT", "DIFF", "haiku")
        assertEquals(listOf("/bin/zsh", "-l", "-c"), inv.command.take(3))
        assertTrue(inv.command[3].contains("claude -p \"\$CLAUDE_COMMIT_PROMPT\" --tools \"\" --no-session-persistence"))
        assertEquals("DIFF", inv.stdin)
        assertEquals(mapOf("CLAUDE_COMMIT_PROMPT" to "PROMPT", "CLAUDE_COMMIT_MODEL" to "haiku"), inv.env)
    }

    @Test
    fun `windows exe is run directly with prompt and diff through stdin`() {
        val inv = ClaudeClient.windowsInvocation("C:\\Users\\Ana\\.local\\bin\\claude.exe", "PROMPT", "DIFF", "")
        assertEquals(listOf("C:\\Users\\Ana\\.local\\bin\\claude.exe", "-p", "--tools=", "--no-session-persistence"), inv.command)
        assertEquals("PROMPT\n\nDiff:\nDIFF", inv.stdin)
        assertTrue(inv.env.isEmpty())
    }

    @Test
    fun `windows npm shim goes through cmd and adds the model`() {
        val inv = ClaudeClient.windowsInvocation("C:\\Users\\Ana Pérez\\AppData\\Roaming\\npm\\claude.CMD", "P", "D", "sonnet")
        assertEquals(listOf("cmd.exe", "/c", "C:\\Users\\Ana Pérez\\AppData\\Roaming\\npm\\claude.CMD"), inv.command.take(3))
        assertEquals(listOf("--model", "sonnet"), inv.command.takeLast(2))
    }

    @Test
    fun `windows arguments contain no empty or quoted values`() {
        // An empty argument would be quoted as "" and make cmd.exe /c strip the quotes around a path with spaces.
        assertFalse(ClaudeClient.claudeArgs("opus").any { it.isEmpty() || it.contains('"') })
    }

    @Test
    fun `finds claude in PATH, preferring the native exe over the npm shim`() {
        val files = setOf("C:\\npm\\claude.cmd", "C:\\tools\\claude.exe")
        val env = mapOf("PATH" to "C:\\npm\\;;\"C:\\tools\";C:\\Windows")
        assertEquals("C:\\tools\\claude.exe", ClaudeClient.findWindowsClaude(env) { it in files })
    }

    @Test
    fun `falls back to the default install locations`() {
        val env = mapOf("Path" to "C:\\Windows", "USERPROFILE" to "C:\\Users\\Ana", "APPDATA" to "C:\\Users\\Ana\\AppData\\Roaming")
        assertEquals(
            "C:\\Users\\Ana\\.local\\bin\\claude.exe",
            ClaudeClient.findWindowsClaude(env) { it == "C:\\Users\\Ana\\.local\\bin\\claude.exe" },
        )
        assertEquals(
            "C:\\Users\\Ana\\AppData\\Roaming\\npm\\claude.cmd",
            ClaudeClient.findWindowsClaude(env) { it == "C:\\Users\\Ana\\AppData\\Roaming\\npm\\claude.cmd" },
        )
    }

    @Test
    fun `returns null when claude is not installed`() {
        assertNull(ClaudeClient.findWindowsClaude(mapOf("PATH" to "C:\\Windows", "USERPROFILE" to "C:\\Users\\Ana")) { false })
    }
}
