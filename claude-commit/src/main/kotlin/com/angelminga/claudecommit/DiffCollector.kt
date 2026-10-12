package com.angelminga.claudecommit

import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import java.io.File
import java.io.StringWriter
import java.nio.file.Path

/**
 * Construye un diff unificado solo con los archivos marcados en el diálogo de commit.
 * Usa las revisiones de IntelliJ (funciona con cualquier VCS) y respeta los fragmentos excluidos en commits parciales.
 */
object DiffCollector {

    private const val MAX_TOTAL_CHARS = 100_000
    private const val MAX_FILE_CHARS = 15_000

    fun collect(project: Project, changes: List<Change>, unversioned: List<FilePath>): String {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val allChanges = changes + unversioned.map { Change(null, CurrentContentRevision.create(it)) }
        val out = StringBuilder()

        for (change in allChanges) {
            ProgressManager.checkCanceled()
            if (out.length >= MAX_TOTAL_CHARS) {
                out.append("\n[... diff truncado: hay más archivos ...]\n")
                break
            }
            val filePath = ChangesUtil.getFilePath(change)
            val root = vcsManager.getVcsRootFor(filePath)?.path ?: project.basePath ?: continue
            val rootDir = File(root)

            val status = when (change.type) {
                Change.Type.NEW -> "NUEVO"
                Change.Type.DELETED -> "ELIMINADO"
                Change.Type.MOVED -> "MOVIDO/RENOMBRADO"
                else -> "MODIFICADO"
            }
            out.append("\n### $status: ${relative(filePath, rootDir)}\n")
            if (change.type == Change.Type.MOVED) {
                change.beforeRevision?.file?.let { out.append("(antes: ${relative(it, rootDir)})\n") }
            }

            val fileDiff = fileDiff(project, change, rootDir)
            out.append(fileDiff.take(MAX_FILE_CHARS))
            if (fileDiff.length > MAX_FILE_CHARS) out.append("\n[... archivo truncado ...]")
            out.append('\n')
        }
        return out.toString()
    }

    private fun fileDiff(project: Project, change: Change, rootDir: File): String {
        if (IdeaTextPatchBuilder.isBinaryRevision(change.beforeRevision) || IdeaTextPatchBuilder.isBinaryRevision(change.afterRevision)) {
            return "[archivo binario]"
        }
        return try {
            val patches = IdeaTextPatchBuilder.buildPatch(project, listOf(change), rootDir.toPath(), false, true)
            StringWriter().also { UnifiedDiffWriter.write(project, patches, it, "\n", CommitContext()) }.toString()
        } catch (e: VcsException) {
            "[no se pudo obtener el diff: ${e.message}]"
        }
    }

    private fun relative(path: FilePath, rootDir: File): String =
        File(path.path).relativeToOrNull(rootDir)?.path ?: path.path
}
