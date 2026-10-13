package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientErrorTest {

    private fun error(exitCode: Int, stdout: String = "", stderr: String = "") =
        ClaudeClient.errorMessage(ShellResult(exitCode, stdout, stderr))

    private val notLoggedIn = "You are not logged in to Claude Code"

    @Test
    fun `successful reply is not an error`() {
        assertNull(error(0, stdout = "TYPE: feat\nREASON: x\nMESSAGE:\nfeat: something"))
    }

    @Test
    fun `successful reply mentioning the error texts is not an error`() {
        assertNull(error(0, stdout = "TYPE: fix\nREASON: x\nMESSAGE:\nfix: handle 'command not found' and 'Not logged in'"))
    }

    @Test
    fun `not logged in (actual CLI output)`() {
        val msg = error(1, stdout = "Not logged in · Please run /login")!!
        assertTrue(msg, msg.startsWith(notLoggedIn))
    }

    @Test
    fun `expired session`() {
        val msg = error(1, stderr = "API Error: 401 OAuth token has expired. Please obtain a new token.")!!
        assertTrue(msg, msg.startsWith(notLoggedIn))
    }

    @Test
    fun `CLI not installed`() {
        val msg = error(127, stderr = "zsh:1: command not found: claude")!!
        assertTrue(msg, msg.startsWith("The \"claude\" CLI was not found"))
    }

    @Test
    fun `other errors show the details`() {
        assertEquals("Claude failed (exit code 2): boom", error(2, stderr = "boom"))
    }

    @Test
    fun `empty output with exit 0 is an error`() {
        assertEquals("Claude failed (exit code 0): ", error(0))
    }
}
