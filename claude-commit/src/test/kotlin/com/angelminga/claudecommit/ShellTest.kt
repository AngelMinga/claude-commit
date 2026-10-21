package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ShellTest {

    private val dir = File(System.getProperty("java.io.tmpdir"))

    // Larger than the pipe buffer (~64 KB) and with non-ASCII characters.
    private val bigInput = "line with accents: áéíóú ñ ✳\n".repeat(10_000)

    @Test
    fun `process that exits without reading stdin returns its exit code instead of Broken pipe`() {
        val res = Shell.run(listOf("/bin/sh", "-c", "exit 127"), dir, stdin = bigInput, timeoutSec = 10)
        assertEquals(127, res.exitCode)
    }

    @Test
    fun `large stdin round trip in UTF-8`() {
        val res = Shell.run(listOf("/bin/cat"), dir, stdin = bigInput, timeoutSec = 10)
        assertEquals(0, res.exitCode)
        assertEquals(bigInput, res.stdout)
    }

    @Test
    fun `stdout lines are delivered while the process is still running`() {
        val seen = mutableListOf<Pair<String, Long>>()
        val start = System.currentTimeMillis()
        val res = Shell.run(listOf("/bin/sh", "-c", "echo one; sleep 1; echo two"), dir, timeoutSec = 10, onStdoutLine = { line ->
            synchronized(seen) { seen += line to System.currentTimeMillis() - start }
        })
        assertEquals("one\ntwo\n", res.stdout)
        assertEquals(listOf("one", "two"), seen.map { it.first })
        assertTrue("first line should arrive before the process ends: $seen", seen[0].second < seen[1].second - 500)
    }

    @Test
    fun `timeout even if the process does not read stdin`() {
        val start = System.currentTimeMillis()
        try {
            Shell.run(listOf("/bin/sleep", "30"), dir, stdin = bigInput, timeoutSec = 1)
            fail("Should have timed out")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Timed out"))
        }
        assertTrue("Took too long to stop", System.currentTimeMillis() - start < 5_000)
    }
}
