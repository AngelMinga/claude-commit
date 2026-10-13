package com.angelminga.claudecommit

import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationAction
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
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.ui.AnimatedIcon
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcs.commit.CommitMessageUi
import java.awt.Component
import java.io.File
import java.io.IOException

/**
 * Button in the commit dialog/panel:
 * 1. Takes the changes checked for commit (including unversioned files).
 * 2. Builds the diff from IntelliJ's revisions.
 * 3. Passes it to `claude -p` (uses your Claude subscription).
 * 4. Shows the detected type (feat, fix…) and fills in the message; the notification offers "Retry".
 */
class GenerateCommitAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        // While a request is running: disabled so it cannot be started twice, and a spinner instead of the icon.
        val running = project?.getUserData(RUNNING) == true
        e.presentation.isEnabled = e.presentation.isVisible && !running
        e.presentation.icon = if (running) AnimatedIcon.Default.INSTANCE else templatePresentation.icon
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val handler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        generate(project, messageControl, handler, previousSuggestion = null)
    }

    /**
     * Runs the whole flow. Also used by the "Retry" notification action, which passes the [previousSuggestion]
     * (null after an error) so Claude writes a different message.
     */
    private fun generate(
        project: Project,
        messageControl: CommitMessageI,
        handler: AbstractCommitWorkflowHandler<*, *>?,
        previousSuggestion: String?,
    ) {
        if (project.isDisposed || project.getUserData(RUNNING) == true) return
        val isRetry = previousSuggestion != null

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

        val messageUi = ui?.commitMessageUi ?: messageControl as? CommitMessageUi
        // When overwriting without asking (or retrying, which asks for a replacement), clear the field right away so
        // it's clear the plugin is working. Keep the text to restore it if Claude fails or the task is cancelled.
        val replaceWithoutAsking = isRetry || ClaudeCommitSettings.getInstance().state.overwriteWithoutAsking
        val previousMessage = messageUi?.text.orEmpty()
        val clearedUpfront = replaceWithoutAsking && previousMessage.isNotBlank()
        if (clearedUpfront) messageControl.setCommitMessage("")
        // Progress hint inside the message field (visible while it's empty).
        val messageField = (messageControl as? CommitMessage)?.editorField
        messageField?.setPlaceholder(ClaudeCommitBundle.message("progress.placeholder"))
        setRunning(project, true)

        val retryWith = { retryFrom: String? -> retry(project, messageControl, handler, retryFrom) }

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
                result = ClaudeClient.suggest(diff, branch, recentCommits, previousSuggestion, indicator)
            }

            override fun onSuccess() {
                val s = result ?: return restorePrevious()
                if (!replaceWithoutAsking && !confirmOverwrite(project, messageUi?.text)) return
                messageControl.setCommitMessage(s.message)
                val detail = buildString {
                    append("<b>${escape(ClaudeCommitBundle.message("notification.detected.type", typeLabel(s.type)))}</b>")
                    if (s.reason.isNotBlank()) append("<br/>${escape(s.reason)}")
                }
                notify(project, detail, NotificationType.INFORMATION) { retryWith(s.message) }
            }

            override fun onThrowable(error: Throwable) {
                restorePrevious()
                // After an error, retry the same request (no previous suggestion to avoid).
                notify(project, escape(error.message ?: error.toString()), NotificationType.ERROR) { retryWith(previousSuggestion) }
            }

            override fun onCancel() {
                restorePrevious()
            }

            /** Puts back the text cleared at the start, unless the user typed something in the meantime. */
            private fun restorePrevious() {
                if (clearedUpfront && messageUi?.text.isNullOrBlank()) messageControl.setCommitMessage(previousMessage)
            }

            override fun onFinished() {
                messageField?.setPlaceholder(VcsBundle.message("commit.message.placeholder"))
                setRunning(project, false)
            }
        })
    }

    /** Updates the running flag and refreshes toolbars right away so the button shows/hides the spinner. */
    private fun setRunning(project: Project, running: Boolean) {
        project.putUserData(RUNNING, if (running) true else null)
        ActivityTracker.getInstance().inc()
    }

    /** "Retry" from a notification: only possible while the commit panel that started the request is still open. */
    private fun retry(project: Project, messageControl: CommitMessageI, handler: AbstractCommitWorkflowHandler<*, *>?, previousSuggestion: String?) {
        if ((messageControl as? Component)?.isShowing == false) {
            notify(project, ClaudeCommitBundle.message("notification.retry.unavailable"), NotificationType.WARNING)
            return
        }
        generate(project, messageControl, handler, previousSuggestion)
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

    private fun notify(project: Project, content: String, type: NotificationType, onRetry: (() -> Unit)? = null) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(ClaudeCommitSettings.PLUGIN_NAME)
            .createNotification(ClaudeCommitSettings.PLUGIN_NAME, content, type)
        if (onRetry != null) {
            notification.addAction(NotificationAction.createSimpleExpiring(ClaudeCommitBundle.message("notification.retry")) { onRetry() })
        }
        notification.notify(project)
    }

    companion object {
        private val RUNNING = Key.create<Boolean>("ClaudeCommit.running")
    }
}
