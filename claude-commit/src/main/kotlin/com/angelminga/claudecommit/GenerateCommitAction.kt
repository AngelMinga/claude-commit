package com.angelminga.claudecommit

import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsBundle
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.vcs.ui.Refreshable
import com.intellij.ui.AnimatedIcon
import com.intellij.vcs.commit.CommitMessageUi
import com.intellij.vcsUtil.VcsUtil
import java.awt.Component
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

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
        // A disabled button paints a grayed-out copy of its icon, which freezes the animation on its first frame.
        // Giving it the animated icon as its disabled icon keeps the spinner moving.
        e.presentation.disabledIcon = if (running) AnimatedIcon.Default.INSTANCE else null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        // Public API for the commit panel (provided next to COMMIT_MESSAGE_CONTROL by the commit workflow).
        val panel = e.getData(Refreshable.PANEL_KEY) as? CheckinProjectPanel
        generate(project, messageControl, panel, previousSuggestion = null)
    }

    /**
     * Runs the whole flow. Also used by the "Retry" notification action, which passes the [previousSuggestion]
     * (null after an error) so Claude writes a different message.
     */
    private fun generate(
        project: Project,
        messageControl: CommitMessageI,
        panel: CheckinProjectPanel?,
        previousSuggestion: String?,
    ) {
        if (project.isDisposed || project.getUserData(RUNNING) == true) return
        val isRetry = previousSuggestion != null

        // Without a commit panel we can't know what is checked, so we use the active changelist.
        // With a panel, we respect the selection.
        val changes: List<Change> = panel?.selectedChanges?.toList()
            ?: ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
        val unversioned: List<FilePath> = panel?.let { checkedUnversionedFiles(it, changes) }.orEmpty()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            notify(project, ClaudeCommitBundle.message("notification.no.files"), NotificationType.WARNING)
            return
        }

        // The diff is built from what is on disk, so save pending edits first.
        FileDocumentManager.getInstance().saveAllDocuments()

        val messageUi = messageControl as? CommitMessageUi
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

        // Streaming: the message is written into the field as it arrives, but only if the field is empty now (it never
        // overwrites the user's text) and only while the user doesn't type in it.
        val streaming = AtomicBoolean(messageUi?.text.isNullOrBlank())
        val lastStreamed = AtomicReference<String?>()
        val modality = (messageControl as? Component)?.let { ModalityState.stateForComponent(it) } ?: ModalityState.defaultModalityState()
        val onPartialText = { raw: String ->
            val text = CommitMessageParser.partialMessage(raw)
            if (text != null && streaming.get()) {
                ApplicationManager.getApplication().invokeLater({
                    val current = messageUi?.text.orEmpty()
                    if (!streaming.get()) return@invokeLater
                    if (current.isBlank() || current == lastStreamed.get()) {
                        messageControl.setCommitMessage(text)
                        lastStreamed.set(text)
                    } else {
                        streaming.set(false) // the user typed something: leave the field alone
                    }
                }, modality)
            }
        }

        // Elapsed seconds in the message field and the progress bar, so it's visible that the request is still running.
        val startedAt = System.currentTimeMillis()
        val taskIndicator = AtomicReference<ProgressIndicator>()
        val ticker = Timer(1000) {
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            messageField?.setPlaceholder(ClaudeCommitBundle.message("progress.placeholder.elapsed", seconds))
            taskIndicator.get()?.text2 = ClaudeCommitBundle.message("progress.elapsed", seconds)
        }
        ticker.start()

        val retryWith = { retryFrom: String? -> retry(project, messageControl, panel, retryFrom) }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, ClaudeCommitBundle.message("task.title"), true) {
            private var result: CommitSuggestion? = null

            override fun run(indicator: ProgressIndicator) {
                taskIndicator.set(indicator)
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

                val projectCommitRules = if (ClaudeCommitSettings.getInstance().state.fastMode) {
                    projectCommitRules(project, repoRoot)
                } else {
                    null
                }

                indicator.text = ClaudeCommitBundle.message("task.asking.claude")
                result = ClaudeClient.suggest(
                    diff, branch, recentCommits, previousSuggestion, projectCommitRules, indicator,
                    onFastModeUnsupported = {
                        notify(project, ClaudeCommitBundle.message("notification.fast.unsupported"), NotificationType.WARNING)
                    },
                    onPartialText = onPartialText,
                )
            }

            override fun onSuccess() {
                val s = result ?: return restorePrevious()
                streaming.set(false)
                // Text the plugin streamed into the field is its own, so it's replaced without asking.
                val current = messageUi?.text
                val ownText = current != null && current == lastStreamed.get()
                if (!replaceWithoutAsking && !ownText && !confirmOverwrite(project, current)) return
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

            /**
             * Puts back the text cleared at the start and removes a partially streamed message, unless the user typed
             * something in the meantime.
             */
            private fun restorePrevious() {
                streaming.set(false)
                val current = messageUi?.text
                val untouched = current.isNullOrBlank() || current == lastStreamed.get()
                if (untouched && (clearedUpfront || lastStreamed.get() != null)) messageControl.setCommitMessage(previousMessage)
            }

            override fun onFinished() {
                streaming.set(false)
                ticker.stop()
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
    private fun retry(project: Project, messageControl: CommitMessageI, panel: CheckinProjectPanel?, previousSuggestion: String?) {
        if ((messageControl as? Component)?.isShowing == false) {
            notify(project, ClaudeCommitBundle.message("notification.retry.unavailable"), NotificationType.WARNING)
            return
        }
        generate(project, messageControl, panel, previousSuggestion)
    }

    /**
     * Checked unversioned files. [CheckinProjectPanel.getVirtualFiles] returns every checked path (the files of the
     * checked changes plus the checked unversioned files), so the unversioned ones are those without a change.
     */
    private fun checkedUnversionedFiles(panel: CheckinProjectPanel, changes: List<Change>): List<FilePath> {
        val changedPaths = changes.mapTo(HashSet()) { ChangesUtil.getFilePath(it).path }
        return panel.virtualFiles.filter { it.path !in changedPaths }.map { VcsUtil.getFilePath(it) }
    }

    /**
     * Commit section of the project's CLAUDE.md (fast mode only: the CLI runs outside the project and doesn't load it).
     * Looks in the repository root and the project directory, at `CLAUDE.md` and `.claude/CLAUDE.md`.
     */
    private fun projectCommitRules(project: Project, repoRoot: File): String? =
        listOfNotNull(repoRoot, project.basePath?.let(::File)).distinct()
            .flatMap { listOf(File(it, "CLAUDE.md"), File(it, ".claude/CLAUDE.md")) }
            .filter { it.isFile && it.length() < MAX_CLAUDE_MD_BYTES }
            .firstNotNullOfOrNull { file -> runCatching { ClaudeClient.commitSection(file.readText(Charsets.UTF_8)) }.getOrNull() }

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
        private const val MAX_CLAUDE_MD_BYTES = 512 * 1024
    }
}
