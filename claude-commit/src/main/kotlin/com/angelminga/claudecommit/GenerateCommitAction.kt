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

/**
 * Botón en el diálogo/panel de commit:
 * 1. Toma los cambios marcados para commit (incluidos los archivos sin versionar).
 * 2. Arma el diff con las revisiones de IntelliJ.
 * 3. Se lo pasa a `claude -p` (usa tu suscripción de Claude).
 * 4. Muestra el tipo detectado (feat, fix…) y prellena el mensaje.
 */
class GenerateCommitAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null
        // Deshabilitado mientras hay una consulta en curso para evitar lanzarla dos veces.
        e.presentation.isEnabled = e.presentation.isVisible && project?.getUserData(RUNNING) != true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        if (project.getUserData(RUNNING) == true) return

        val handler = e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>
        val ui = handler?.ui
        // Sin panel de commit no sabemos qué está marcado: usamos la changelist activa. Con panel, respetamos la selección.
        val changes: List<Change> = ui?.getIncludedChanges()
            ?: ChangeListManager.getInstance(project).defaultChangeList.changes.toList()
        val unversioned: List<FilePath> = ui?.getIncludedUnversionedFiles().orEmpty()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            notify(project, "Marca al menos un archivo para el commit.", NotificationType.WARNING)
            return
        }

        // El diff se arma con lo que hay en disco: guardamos antes las ediciones pendientes.
        FileDocumentManager.getInstance().saveAllDocuments()

        val messageUi = handler?.ui?.commitMessageUi ?: messageControl as? CommitMessageUi
        val commitMessage = messageControl as CommitMessageI
        // Si se reemplaza sin preguntar, vaciamos el campo ya para que se vea que está trabajando.
        // Guardamos el texto para restaurarlo si Claude falla o se cancela.
        val previousMessage = messageUi?.text.orEmpty()
        val clearedUpfront = ClaudeCommitSettings.getInstance().state.overwriteWithoutAsking && previousMessage.isNotBlank()
        if (clearedUpfront) commitMessage.setCommitMessage("")
        project.putUserData(RUNNING, true)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Claude analizando cambios…", true) {
            private var result: CommitSuggestion? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Preparando diff…"
                val diff = DiffCollector.collect(project, changes, unversioned)
                if (diff.isBlank()) throw IllegalStateException("No se pudo obtener el diff de los cambios.")

                val repoRoot = firstRoot(project, changes, unversioned)
                val branch = Shell.run(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), repoRoot, timeoutSec = 10)
                    .stdout.trim()

                indicator.text = "Consultando a Claude…"
                result = ClaudeClient.suggest(diff, branch, indicator)
            }

            override fun onSuccess() {
                val s = result ?: return restorePrevious()
                if (!confirmOverwrite(project, messageUi?.text)) return
                commitMessage.setCommitMessage(s.message)
                val detail = buildString {
                    append("<b>Tipo detectado: ${s.type}</b>")
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

            /** Devuelve el texto vaciado al empezar, salvo que el usuario haya escrito algo mientras tanto. */
            private fun restorePrevious() {
                if (clearedUpfront && messageUi?.text.isNullOrBlank()) commitMessage.setCommitMessage(previousMessage)
            }

            override fun onFinished() {
                project.putUserData(RUNNING, null)
            }
        })
    }

    private fun confirmOverwrite(project: Project, current: String?): Boolean {
        if (current.isNullOrBlank() || ClaudeCommitSettings.getInstance().state.overwriteWithoutAsking) return true
        return MessageDialogBuilder.yesNo(ClaudeCommitSettings.PLUGIN_NAME, "Ya hay un mensaje de commit escrito. ¿Reemplazarlo por el generado?")
            .yesText("Reemplazar")
            .noText("Conservar")
            .ask(project)
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
