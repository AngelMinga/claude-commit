package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientErrorTest {

    private fun error(exitCode: Int, stdout: String = "", stderr: String = "") =
        ClaudeClient.errorMessage(ShellResult(exitCode, stdout, stderr))

    @Test
    fun `respuesta correcta no es error`() {
        assertNull(error(0, stdout = "TIPO: feat\nRAZON: x\nMENSAJE:\nfeat: algo"))
    }

    @Test
    fun `respuesta correcta que menciona los textos de error no es error`() {
        assertNull(error(0, stdout = "TIPO: fix\nRAZON: x\nMENSAJE:\nfix: evitar 'command not found' y 'Not logged in'"))
    }

    @Test
    fun `sin sesión iniciada (salida real de la CLI)`() {
        val msg = error(1, stdout = "Not logged in · Please run /login")!!
        assertTrue(msg, msg.startsWith("No has iniciado sesión en Claude Code"))
    }

    @Test
    fun `sesión caducada`() {
        val msg = error(1, stderr = "API Error: 401 OAuth token has expired. Please obtain a new token.")!!
        assertTrue(msg, msg.startsWith("No has iniciado sesión en Claude Code"))
    }

    @Test
    fun `CLI no instalada`() {
        val msg = error(127, stderr = "zsh:1: command not found: claude")!!
        assertTrue(msg, msg.startsWith("No se encontró la CLI 'claude'"))
    }

    @Test
    fun `otro error muestra el detalle`() {
        assertEquals("Claude falló (código 2): boom", error(2, stderr = "boom"))
    }

    @Test
    fun `salida vacía con exit 0 es error`() {
        assertEquals("Claude falló (código 0): ", error(0))
    }
}
