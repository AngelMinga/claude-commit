package com.angelminga.claudecommit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import java.io.File

/**
 * Botón en el diálogo/panel de commit:
 * 1. Toma los cambios marcados para commit.
 * 2. Arma el diff con git.
 * 3. Se lo pasa a `claude -p` (usa tu suscripción de Claude).
 * 4. Muestra el tipo detectado (feat, fix…) y prellena el mensaje.
 */
class GenerateCommitAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return

        val handler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        var changes: List<Change> = handler?.ui?.getIncludedChanges().orEmpty()
        if (changes.isEmpty()) {
            changes = ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
        }
        if (changes.isEmpty()) {
            notify(project, "No hay cambios seleccionados para el commit.", NotificationType.WARNING)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Claude analizando cambios…", true) {
            private var result: CommitSuggestion? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Preparando diff…"
                val diff = DiffCollector.collect(project, changes)
                if (diff.isBlank()) throw IllegalStateException("No se pudo obtener el diff de los cambios.")

                val workDir = firstRoot(project, changes)
                val branch = Shell.run(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), workDir, timeoutSec = 10)
                    .stdout.trim()

                indicator.text = "Consultando a Claude…"
                result = ClaudeClient.suggest(diff, branch, workDir, indicator)
            }

            override fun onSuccess() {
                val s = result ?: return
                (messageControl as CommitMessageI).setCommitMessage(s.message)
                val detail = buildString {
                    append("<b>Tipo detectado: ${s.type}</b>")
                    if (s.reason.isNotBlank()) append("<br/>${escape(s.reason)}")
                }
                notify(project, detail, NotificationType.INFORMATION)
            }

            override fun onThrowable(error: Throwable) {
                notify(project, escape(error.message ?: error.toString()), NotificationType.ERROR)
            }
        })
    }

    private fun firstRoot(project: Project, changes: List<Change>): File {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val root = changes.firstNotNullOfOrNull { vcsManager.getVcsRootFor(ChangesUtil.getFilePath(it)) }
        return File(root?.path ?: project.basePath ?: ".")
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Claude Commit")
            .createNotification("Claude Commit", content, type)
            .notify(project)
    }
}
