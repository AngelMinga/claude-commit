package com.angelminga.claudecommit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcs.commit.CommitMessageUi
import java.io.File
import java.io.IOException

/**
 * Button in the commit dialog/panel:
 * 1. Takes the changes checked for commit (including unversioned files).
 * 2. Builds the diff from IntelliJ's revisions.
 * 3. Passes it to `claude -p` (uses your Claude subscription).
 * 4. Shows the detected type (feat, fix…) and fills in the message.
 */
class GenerateCommitAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        // Disabled while a request is running so it cannot be started twice.
        e.presentation.isEnabled = e.presentation.isVisible && project?.getUserData(RUNNING) != true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        if (project.getUserData(RUNNING) == true) return

        val handler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        val ui = handler?.ui
        // Without a commit panel we can't know what is checked, so we use the active changelist.
        // With a panel, we respect the selection.
        val changes: List<Change> = ui?.getIncludedChanges()
            ?: ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
        val unversioned: List<FilePath> = ui?.getIncludedUnversionedFiles().orEmpty()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            notify(project, ClaudeCommitBundle.message("notification.no.files"), NotificationType.WARNING)
            return
        }

        // The diff is built from what is on disk, so save pending edits first.
        FileDocumentManager.getInstance().saveAllDocuments()

        val messageUi = handler?.ui?.commitMessageUi ?: messageControl as? CommitMessageUi
        val commitMessage = messageControl as CommitMessageI
        // When overwriting without asking, clear the field right away so it's clear the plugin is working.
        // Keep the text to restore it if Claude fails or the task is cancelled.
        val previousMessage = messageUi?.text.orEmpty()
        val clearedUpfront = ClaudeCommitSettings.getInstance().state.overwriteWithoutAsking && previousMessage.isNotBlank()
        if (clearedUpfront) commitMessage.setCommitMessage("")
        project.putUserData(RUNNING, true)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, ClaudeCommitBundle.message("task.title"), true) {
            private var result: CommitSuggestion? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = ClaudeCommitBundle.message("task.preparing.diff")
                val diff = DiffCollector.collect(project, changes, unversioned)
                if (diff.isBlank()) throw IllegalStateException(ClaudeCommitBundle.message("error.empty.diff"))

                val repoRoot = firstRoot(project, changes, unversioned)
                val branch = git(repoRoot, "rev-parse", "--abbrev-ref", "HEAD")?.trim().orEmpty()

                val recentCommits = if (ClaudeCommitSettings.getInstance().state.imitateRepoStyle) {
                    recentCommitSubjects(repoRoot)
                } else {
                    emptyList()
                }

                indicator.text = ClaudeCommitBundle.message("task.asking.claude")
                result = ClaudeClient.suggest(diff, branch, recentCommits, indicator)
            }

            override fun onSuccess() {
                val s = result ?: return restorePrevious()
                if (!confirmOverwrite(project, messageUi?.text)) return
                commitMessage.setCommitMessage(s.message)
                val detail = buildString {
                    append("<b>${escape(ClaudeCommitBundle.message("notification.detected.type", typeLabel(s.type)))}</b>")
                    if (s.reason.isNotBlank()) append("<br/>${escape(s.reason)}")
                }
                notify(project, detail, NotificationType.INFORMATION)
            }

            override fun onThrowable(error: Throwable) {
                restorePrevious()
                notify(project, escape(error.message ?: error.toString()), NotificationType.ERROR)
            }

            override fun onCancel() {
                restorePrevious()
            }

            /** Puts back the text cleared at the start, unless the user typed something in the meantime. */
            private fun restorePrevious() {
                if (clearedUpfront && messageUi?.text.isNullOrBlank()) commitMessage.setCommitMessage(previousMessage)
            }

            override fun onFinished() {
                project.putUserData(RUNNING, null)
            }
        })
    }

    /** Titles of the latest commits (no merges); empty if it isn't a git repo or has no commits yet. */
    private fun recentCommitSubjects(repoRoot: File): List<String> =
        git(repoRoot, "log", "-n", ClaudeClient.RECENT_COMMITS.toString(), "--no-merges", "--format=%s")
            ?.let { ClaudeClient.parseRecentCommits(it) }
            .orEmpty()

    /** Output of a git command, or null if it fails or git isn't installed (e.g. projects using another VCS). */
    private fun git(repoRoot: File, vararg args: String): String? = try {
        Shell.run(listOf("git", *args), repoRoot, timeoutSec = 10).takeIf { it.exitCode == 0 }?.stdout
    } catch (_: IOException) {
        null
    }

    private fun confirmOverwrite(project: Project, current: String?): Boolean {
        if (current.isNullOrBlank() || ClaudeCommitSettings.getInstance().state.overwriteWithoutAsking) return true
        return MessageDialogBuilder.yesNo(ClaudeCommitSettings.PLUGIN_NAME, ClaudeCommitBundle.message("dialog.overwrite.message"))
            .yesText(ClaudeCommitBundle.message("dialog.overwrite.replace"))
            .noText(ClaudeCommitBundle.message("dialog.overwrite.keep"))
            .ask(project)
    }

    private fun typeLabel(type: String): String = when {
        type.isBlank() -> ClaudeCommitBundle.message("notification.type.unknown")
        type in CommitMessageParser.TYPES -> type
        else -> ClaudeCommitBundle.message("notification.type.nonstandard", type)
    }

    private fun firstRoot(project: Project, changes: List<Change>, unversioned: List<FilePath>): File {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val paths = changes.map { ChangesUtil.getFilePath(it) } + unversioned
        val root = paths.firstNotNullOfOrNull { vcsManager.getVcsRootFor(it) }
        return File(root?.path ?: project.basePath ?: ".")
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeCommitSettings.PLUGIN_NAME)
            .createNotification(ClaudeCommitSettings.PLUGIN_NAME, content, type)
            .notify(project)
    }

    companion object {
        private val RUNNING = Key.create<Boolean>("ClaudeCommit.running")
    }
}
