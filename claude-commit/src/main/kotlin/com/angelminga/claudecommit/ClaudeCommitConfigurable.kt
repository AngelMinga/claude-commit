package com.angelminga.claudecommit

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

class ClaudeCommitConfigurable : BoundConfigurable("Claude Commit") {

    /** [value] es lo que se guarda (y se pasa a la CLI o al prompt); [label] es lo que ve el usuario. */
    private data class Option(val value: String, val label: String)

    private val settings get() = ClaudeCommitSettings.getInstance().state

    private val models = listOf(
        Option("", "Por defecto de la CLI"),
        Option("haiku", "Haiku — más rápido"),
        Option("sonnet", "Sonnet — equilibrado"),
        Option("opus", "Opus — más capaz"),
    )

    private val languages = listOf(
        Option("español", "Español"),
        Option("inglés", "Inglés"),
        Option("portugués", "Portugués"),
        Option("francés", "Francés"),
        Option("alemán", "Alemán"),
        Option("italiano", "Italiano"),
    )

    override fun createPanel(): DialogPanel {
        val modelOptions = withCurrent(models, settings.model.orEmpty().trim())
        val languageOptions = withCurrent(languages, settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE)

        return panel {
            row("Modelo:") {
                comboBox(modelOptions, renderer())
                    .bindItem(
                        { modelOptions.firstOrNull { it.value == settings.model.orEmpty().trim() } ?: modelOptions.first() },
                        { settings.model = it?.value.orEmpty() },
                    )
                    .comment("Se pasa a la CLI como <code>--model</code>.")
            }
            row("Idioma del mensaje:") {
                comboBox(languageOptions, renderer())
                    .bindItem(
                        {
                            val current = settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE
                            languageOptions.firstOrNull { it.value == current } ?: languageOptions.first()
                        },
                        { settings.language = it?.value ?: ClaudeCommitSettings.DEFAULT_LANGUAGE },
                    )
            }
            row("Tiempo máximo (segundos):") {
                intTextField(ClaudeCommitSettings.MIN_TIMEOUT_SEC..ClaudeCommitSettings.MAX_TIMEOUT_SEC)
                    .bindIntText({ settings.timeoutSec }, { settings.timeoutSec = it })
            }
            row {
                checkBox("Reemplazar el mensaje existente sin preguntar")
                    .bindSelected({ settings.overwriteWithoutAsking }, { settings.overwriteWithoutAsking = it })
            }
        }
    }

    /** Conserva un valor guardado que no esté en la lista (p. ej. un modelo escrito a mano en versiones anteriores). */
    private fun withCurrent(options: List<Option>, current: String): List<Option> =
        if (current.isEmpty() || options.any { it.value == current }) options else options + Option(current, current)

    private fun renderer() = textListCellRenderer<Option?>("") { it?.label }
}
