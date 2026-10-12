package com.angelminga.claudecommit

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

class ClaudeCommitConfigurable : BoundConfigurable(ClaudeCommitSettings.PLUGIN_NAME) {

    /** [value] is what gets stored (and passed to the CLI or the prompt); [label] is what the user sees. */
    private data class Option(val value: String, val label: String)

    private val settings get() = ClaudeCommitSettings.getInstance().state

    private val models = listOf(
        Option("", "CLI default"),
        Option("haiku", "Haiku — fastest"),
        Option("sonnet", "Sonnet — balanced"),
        Option("opus", "Opus — most capable"),
    )

    private val languages = listOf("English", "Spanish", "Portuguese", "French", "German", "Italian")
        .map { Option(it, it) }

    override fun createPanel(): DialogPanel {
        val modelOptions = withCurrent(models, settings.model.orEmpty().trim())
        val languageOptions = withCurrent(languages, settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE)

        return panel {
            row("Model:") {
                comboBox(modelOptions, renderer())
                    .bindItem(
                        { modelOptions.firstOrNull { it.value == settings.model.orEmpty().trim() } ?: modelOptions.first() },
                        { settings.model = it?.value.orEmpty() },
                    )
                    .comment("Passed to the CLI as <code>--model</code>.")
            }
            row("Message language:") {
                comboBox(languageOptions, renderer())
                    .bindItem(
                        {
                            val current = settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE
                            languageOptions.firstOrNull { it.value == current } ?: languageOptions.first()
                        },
                        { settings.language = it?.value ?: ClaudeCommitSettings.DEFAULT_LANGUAGE },
                    )
            }
            row("Timeout (seconds):") {
                intTextField(ClaudeCommitSettings.MIN_TIMEOUT_SEC..ClaudeCommitSettings.MAX_TIMEOUT_SEC)
                    .bindIntText({ settings.timeoutSec }, { settings.timeoutSec = it })
            }
            row {
                checkBox("Match the style of recent commits in the repository")
                    .bindSelected({ settings.imitateRepoStyle }, { settings.imitateRepoStyle = it })
                    .comment("Sends the titles of the last ${ClaudeClient.RECENT_COMMITS} commits to Claude as a reference.")
            }
            row {
                checkBox("Replace the existing message without asking")
                    .bindSelected({ settings.overwriteWithoutAsking }, { settings.overwriteWithoutAsking = it })
            }
        }
    }

    /** Keeps a stored value that isn't in the list (e.g. a model typed by hand in older versions). */
    private fun withCurrent(options: List<Option>, current: String): List<Option> =
        if (current.isEmpty() || options.any { it.value == current }) options else options + Option(current, current)

    private fun renderer() = textListCellRenderer<Option?>("") { it?.label }
}
