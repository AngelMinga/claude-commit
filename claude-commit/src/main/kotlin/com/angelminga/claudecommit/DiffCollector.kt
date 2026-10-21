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

/**
 * Builds a unified diff with only the files checked in the commit dialog.
 * Uses IntelliJ's revisions (works with any VCS) and honors the hunks excluded in partial commits.
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
                out.append("\n[... diff truncated: more files not shown ...]\n")
                break
            }
            val filePath = ChangesUtil.getFilePath(change)
            val root = vcsManager.getVcsRootFor(filePath)?.path ?: project.basePath ?: continue
            val rootDir = File(root)

            val status = when (change.type) {
                Change.Type.NEW -> "NEW"
                Change.Type.DELETED -> "DELETED"
                Change.Type.MOVED -> "MOVED/RENAMED"
                else -> "MODIFIED"
            }
            out.append("\n### $status: ${relative(filePath, rootDir)}\n")
            if (change.type == Change.Type.MOVED) {
                change.beforeRevision?.file?.let { out.append("(previously: ${relative(it, rootDir)})\n") }
            }

            // Generated files, lock files and minified bundles are listed but their content is left out: it's noise for
            // the message and wastes most of the size budget.
            val omitted = omittedReason(relative(filePath, rootDir))
            if (omitted != null) {
                out.append("[$omitted: content omitted]\n")
                continue
            }

            val fileDiff = fileDiff(project, change, rootDir)
            out.append(fileDiff.take(MAX_FILE_CHARS))
            if (fileDiff.length > MAX_FILE_CHARS) out.append("\n[... file truncated ...]")
            out.append('\n')
        }
        return out.toString()
    }

    private fun fileDiff(project: Project, change: Change, rootDir: File): String {
        if (IdeaTextPatchBuilder.isBinaryRevision(change.beforeRevision) || IdeaTextPatchBuilder.isBinaryRevision(change.afterRevision)) {
            return "[binary file]"
        }
        return try {
            val patches = IdeaTextPatchBuilder.buildPatch(project, listOf(change), rootDir.toPath(), false, true)
            StringWriter().also { UnifiedDiffWriter.write(project, patches, it, "\n", CommitContext()) }.toString()
        } catch (e: VcsException) {
            "[could not build the diff: ${e.message}]"
        }
    }

    private val GENERATED_DIRS = setOf(
        "build", "dist", "out", "target", "bin", "node_modules", ".gradle", "coverage", "__pycache__", ".next", ".nuxt",
    )
    private val LOCK_FILES = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "Cargo.lock", "Gemfile.lock", "poetry.lock",
        "Pipfile.lock", "composer.lock", "Podfile.lock", "pubspec.lock", "go.sum", "gradle.lockfile", "flake.lock",
        "mix.lock", "Package.resolved", "packages.lock.json",
    )
    private val MINIFIED = Regex("""\.min\.(js|css)$|\.(js|css)\.map$""", RegexOption.IGNORE_CASE)

    /**
     * Why a file's content should be left out of the diff, or null to include it. Generated directories only count
     * outside source folders (a segment before them named `src`), so packages such as `com/acme/build` are kept.
     */
    internal fun omittedReason(relativePath: String): String? {
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val name = segments.lastOrNull() ?: return null
        if (name in LOCK_FILES) return "lock file"
        if (MINIFIED.containsMatchIn(name)) return "minified file or source map"
        val dirs = segments.dropLast(1)
        val srcIndex = dirs.indexOf("src").let { if (it < 0) Int.MAX_VALUE else it }
        if (dirs.withIndex().any { (i, d) -> i < srcIndex && d in GENERATED_DIRS }) return "generated or build output"
        return null
    }

    private fun relative(path: FilePath, rootDir: File): String =
        File(path.path).relativeToOrNull(rootDir)?.path ?: path.path
}
