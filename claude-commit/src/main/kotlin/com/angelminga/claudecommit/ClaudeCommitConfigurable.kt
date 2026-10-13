package com.angelminga.claudecommit

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer

class ClaudeCommitConfigurable : BoundConfigurable(ClaudeCommitBundle.message("settings.display.name")) {

    /** [value] is what gets stored (and passed to the CLI or the prompt); [label] is what the user sees. */
    private data class Option(val value: String, val label: String)

    private val settings get() = ClaudeCommitSettings.getInstance().state

    private val models = listOf(
        Option("", ClaudeCommitBundle.message("settings.model.default")),
        Option("haiku", ClaudeCommitBundle.message("settings.model.haiku")),
        Option("sonnet", ClaudeCommitBundle.message("settings.model.sonnet")),
        Option("opus", ClaudeCommitBundle.message("settings.model.opus")),
    )

    // The stored value stays in English because it is inserted into the (English) prompt; only the label is localized.
    private val languages = listOf("English", "Spanish", "Portuguese", "French", "German", "Italian")
        .map { Option(it, ClaudeCommitBundle.message("settings.language.$it")) }

    override fun createPanel(): DialogPanel {
        val modelOptions = withCurrent(models, settings.model.orEmpty().trim())
        val languageOptions = withCurrent(languages, settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE)

        return panel {
            row(ClaudeCommitBundle.message("settings.model")) {
                comboBox(modelOptions, renderer())
                    .bindItem(
                        { modelOptions.firstOrNull { it.value == settings.model.orEmpty().trim() } ?: modelOptions.first() },
                        { settings.model = it?.value.orEmpty() },
                    )
                    .comment(ClaudeCommitBundle.message("settings.model.comment"))
            }
            row(ClaudeCommitBundle.message("settings.language")) {
                comboBox(languageOptions, renderer())
                    .bindItem(
                        {
                            val current = settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE
                            languageOptions.firstOrNull { it.value == current } ?: languageOptions.first()
                        },
                        { settings.language = it?.value ?: ClaudeCommitSettings.DEFAULT_LANGUAGE },
                    )
            }
            row(ClaudeCommitBundle.message("settings.timeout")) {
                intTextField(ClaudeCommitSettings.MIN_TIMEOUT_SEC..ClaudeCommitSettings.MAX_TIMEOUT_SEC)
                    .bindIntText({ settings.timeoutSec }, { settings.timeoutSec = it })
            }
            row {
                checkBox(ClaudeCommitBundle.message("settings.imitate"))
                    .bindSelected({ settings.imitateRepoStyle }, { settings.imitateRepoStyle = it })
                    .comment(ClaudeCommitBundle.message("settings.imitate.comment", ClaudeClient.RECENT_COMMITS))
            }
            row {
                checkBox(ClaudeCommitBundle.message("settings.overwrite"))
                    .bindSelected({ settings.overwriteWithoutAsking }, { settings.overwriteWithoutAsking = it })
            }
        }
    }

    /** Keeps a stored value that isn't in the list (e.g. a model typed by hand in older versions). */
    private fun withCurrent(options: List<Option>, current: String): List<Option> =
        if (current.isEmpty() || options.any { it.value == current }) options else options + Option(current, current)

    private fun renderer() = textListCellRenderer<Option?>("") { it?.label }
}
