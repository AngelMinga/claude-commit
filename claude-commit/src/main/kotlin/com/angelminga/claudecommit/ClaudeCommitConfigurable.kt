package com.angelminga.claudecommit

import com.intellij.DynamicBundle
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class ClaudeCommitConfigurable : BoundConfigurable(ClaudeCommitBundle.message("settings.display.name")) {

    /** [value] is what gets stored (and passed to the CLI or the prompt); [label] is what the user sees. */
    private data class Option(val value: String, val label: String) {
        // The combo box's default renderer shows toString().
        override fun toString() = label
    }

    private val settings get() = ClaudeCommitSettings.getInstance().state

    // CLI aliases: each one always points to the latest model of its family (checked with Claude Code 2.1.296).
    private val models = listOf(
        Option("", ClaudeCommitBundle.message("settings.model.default")),
        Option("haiku", ClaudeCommitBundle.message("settings.model.haiku")),
        Option("sonnet", ClaudeCommitBundle.message("settings.model.sonnet")),
        Option("opus", ClaudeCommitBundle.message("settings.model.opus")),
        Option("fable", ClaudeCommitBundle.message("settings.model.fable")),
    )

    // Keys are written out (not built from the value) so the IDE and @PropertyKey can check that they exist.
    private val efforts = listOf(
        Option("", ClaudeCommitBundle.message("settings.effort.default")),
        Option("low", ClaudeCommitBundle.message("settings.effort.low")),
        Option("medium", ClaudeCommitBundle.message("settings.effort.medium")),
        Option("high", ClaudeCommitBundle.message("settings.effort.high")),
        Option("xhigh", ClaudeCommitBundle.message("settings.effort.xhigh")),
        Option("max", ClaudeCommitBundle.message("settings.effort.max")),
    )

    // The stored value stays in English because it is inserted into the (English) prompt; only the label is localized.
    // The first option follows the IDE language; its label shows which language that currently is.
    private val languages = listOf(
        Option(ClaudeCommitSettings.LANGUAGE_IDE, ideLanguageLabel()),
        Option("English", ClaudeCommitBundle.message("settings.language.English")),
        Option("Spanish", ClaudeCommitBundle.message("settings.language.Spanish")),
        Option("Portuguese", ClaudeCommitBundle.message("settings.language.Portuguese")),
        Option("French", ClaudeCommitBundle.message("settings.language.French")),
        Option("German", ClaudeCommitBundle.message("settings.language.German")),
        Option("Italian", ClaudeCommitBundle.message("settings.language.Italian")),
    )

    override fun createPanel(): DialogPanel {
        val modelOptions = withCurrent(models, settings.model.orEmpty().trim())
        val effortOptions = withCurrent(efforts, settings.effort.orEmpty().trim())
        val languageOptions = withCurrent(languages, settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE)

        return panel {
            row(ClaudeCommitBundle.message("settings.model")) {
                comboBox(modelOptions)
                    .bindItem(
                        { modelOptions.firstOrNull { it.value == settings.model.orEmpty().trim() } ?: modelOptions.first() },
                        { settings.model = it?.value.orEmpty() },
                    )
                    .comment(ClaudeCommitBundle.message("settings.model.comment"))
            }
            row(ClaudeCommitBundle.message("settings.model.custom")) {
                textField()
                    .bindText({ settings.customModel.orEmpty() }, { settings.customModel = it.trim() })
                    .comment(ClaudeCommitBundle.message("settings.model.custom.comment"))
            }
            row(ClaudeCommitBundle.message("settings.effort")) {
                comboBox(effortOptions)
                    .bindItem(
                        { effortOptions.firstOrNull { it.value == settings.effort.orEmpty().trim() } ?: effortOptions.first() },
                        { settings.effort = it?.value.orEmpty() },
                    )
                    .comment(ClaudeCommitBundle.message("settings.effort.comment"))
            }
            row(ClaudeCommitBundle.message("settings.language")) {
                comboBox(languageOptions)
                    .bindItem(
                        {
                            val current = settings.language?.trim() ?: ClaudeCommitSettings.DEFAULT_LANGUAGE
                            languageOptions.firstOrNull { it.value == current } ?: languageOptions.first()
                        },
                        { settings.language = it?.value ?: ClaudeCommitSettings.DEFAULT_LANGUAGE },
                    )
            }
            row {
                checkBox(ClaudeCommitBundle.message("settings.fast"))
                    .bindSelected({ settings.fastMode }, { settings.fastMode = it })
                    .comment(ClaudeCommitBundle.message("settings.fast.comment"))
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
                checkBox(ClaudeCommitBundle.message("settings.scope"))
                    .bindSelected({ settings.alwaysIncludeScope }, { settings.alwaysIncludeScope = it })
                    .comment(ClaudeCommitBundle.message("settings.scope.comment"))
            }
            row(ClaudeCommitBundle.message("settings.extra")) {
                textArea()
                    .rows(3)
                    .align(AlignX.FILL)
                    .bindText(
                        { settings.extraInstructions.orEmpty() },
                        { settings.extraInstructions = it.trim().take(ClaudeCommitSettings.MAX_EXTRA_INSTRUCTIONS) },
                    )
                    .comment(ClaudeCommitBundle.message("settings.extra.comment"))
            }
            row {
                checkBox(ClaudeCommitBundle.message("settings.overwrite"))
                    .bindSelected({ settings.overwriteWithoutAsking }, { settings.overwriteWithoutAsking = it })
            }
            row(ClaudeCommitBundle.message("settings.cli.path")) {
                textField()
                    .align(AlignX.FILL)
                    .bindText({ settings.claudePath.orEmpty() }, { settings.claudePath = it.trim() })
                    .comment(ClaudeCommitBundle.message("settings.cli.path.comment"))
            }
        }
    }

    /** Keeps a stored value that isn't in the list (e.g. a model typed by hand in older versions). */
    private fun withCurrent(options: List<Option>, current: String): List<Option> =
        if (current.isEmpty() || options.any { it.value == current }) options else options + Option(current, current)

    /** "Same as IDE (English)", with the language name written in the IDE language. */
    private fun ideLanguageLabel(): String {
        val locale = DynamicBundle.getLocale()
        val name = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
        return ClaudeCommitBundle.message("settings.language.ide", name)
    }
}
