package com.angelminga.claudecommit

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
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

        val executor = AppExecutorUtil.getAppExecutorService()
        val out = executor.submit(Callable { process.inputStream.bufferedReader(Charsets.UTF_8).readText() })
        val err = executor.submit(Callable { process.errorStream.bufferedReader(Charsets.UTF_8).readText() })

        // stdin en otro hilo: si el proceso tarda en leer, la escritura se bloquea con diffs mayores que el buffer
        // del pipe y no podríamos atender la cancelación ni el timeout.
        executor.execute {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { w -> if (stdin != null) w.write(stdin) }
            } catch (_: IOException) {
                // El proceso terminó sin leer todo (p. ej. CLI no encontrada o proceso cancelado): lo informa el exit code.
            }
        }

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
