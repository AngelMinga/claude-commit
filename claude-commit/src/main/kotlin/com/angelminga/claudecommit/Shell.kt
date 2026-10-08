package com.angelminga.claudecommit

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

object Shell {

    fun run(
        command: List<String>,
        dir: File,
        stdin: String? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long,
        indicator: ProgressIndicator? = null,
    ): ShellResult {
        val process = ProcessBuilder(command)
            .directory(dir)
            .apply { environment().putAll(env) }
            .start()

        val out = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        val err = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }

        process.outputStream.bufferedWriter().use { w -> if (stdin != null) w.write(stdin) }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (indicator?.isCanceled == true) {
                process.destroyForcibly()
                throw ProcessCanceledException()
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                throw IllegalStateException("Tiempo de espera agotado (${timeoutSec}s) ejecutando: ${command.first()}")
            }
        }
        return ShellResult(process.exitValue(), out.get(), err.get())
    }
}
