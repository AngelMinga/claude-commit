package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ShellTest {

    private val dir = File(System.getProperty("java.io.tmpdir"))

    // Mayor que el buffer del pipe (~64 KB) y con caracteres no ASCII.
    private val bigInput = "línea con acentos: áéíóú ñ ✳\n".repeat(10_000)

    @Test
    fun `proceso que termina sin leer stdin devuelve su exit code en vez de Broken pipe`() {
        val res = Shell.run(listOf("/bin/sh", "-c", "exit 127"), dir, stdin = bigInput, timeoutSec = 10)
        assertEquals(127, res.exitCode)
    }

    @Test
    fun `stdin grande ida y vuelta en UTF-8`() {
        val res = Shell.run(listOf("/bin/cat"), dir, stdin = bigInput, timeoutSec = 10)
        assertEquals(0, res.exitCode)
        assertEquals(bigInput, res.stdout)
    }

    @Test
    fun `timeout aunque el proceso no lea stdin`() {
        val start = System.currentTimeMillis()
        try {
            Shell.run(listOf("/bin/sleep", "30"), dir, stdin = bigInput, timeoutSec = 1)
            fail("Debería haber agotado el tiempo")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Tiempo de espera agotado"))
        }
        assertTrue("Tardó demasiado en cortar", System.currentTimeMillis() - start < 5_000)
    }
}
