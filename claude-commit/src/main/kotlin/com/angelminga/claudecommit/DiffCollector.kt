package com.angelminga.claudecommit

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import java.io.File

/** Construye un diff legible solo con los archivos marcados en el diálogo de commit. */
object DiffCollector {

    private const val MAX_TOTAL_CHARS = 100_000
    private const val MAX_FILE_CHARS = 15_000

    fun collect(project: Project, changes: List<Change>): String {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val out = StringBuilder()

        for (change in changes) {
            if (out.length >= MAX_TOTAL_CHARS) {
                out.append("\n[... diff truncado: hay más archivos ...]\n")
                break
            }
            val filePath = ChangesUtil.getFilePath(change)
            val root = vcsManager.getVcsRootFor(filePath)?.path ?: project.basePath ?: continue
            val rootDir = File(root)
            val relative = File(filePath.path).relativeToOrNull(rootDir)?.path ?: filePath.path

            val status = when (change.type) {
                Change.Type.NEW -> "NUEVO"
                Change.Type.DELETED -> "ELIMINADO"
                Change.Type.MOVED -> "MOVIDO/RENOMBRADO"
                else -> "MODIFICADO"
            }
            out.append("\n### $status: $relative\n")

            change.beforeRevision?.file?.path?.let { before ->
                if (change.type == Change.Type.MOVED) {
                    val beforeRel = File(before).relativeToOrNull(rootDir)?.path ?: before
                    out.append("(antes: $beforeRel)\n")
                }
            }

            var fileDiff = Shell.run(listOf("git", "diff", "HEAD", "--", relative), rootDir, timeoutSec = 20).stdout

            // Archivo nuevo sin agregar a git o repo sin commits: usamos el contenido.
            if (fileDiff.isBlank() && change.type == Change.Type.NEW) {
                val f = File(filePath.path)
                if (f.isFile) {
                    fileDiff = runCatching { f.readText() }.getOrDefault("[archivo binario o ilegible]")
                        .lineSequence().joinToString("\n") { "+$it" }
                }
            }

            out.append(fileDiff.take(MAX_FILE_CHARS))
            if (fileDiff.length > MAX_FILE_CHARS) out.append("\n[... archivo truncado ...]")
            out.append('\n')
        }
        return out.toString()
    }
}
