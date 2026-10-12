package com.angelminga.claudecommit

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Global plugin settings (Settings → Tools → Commit Messages for Claude Code). */
@Service(Service.Level.APP)
@State(name = "ClaudeCommitSettings", storages = [Storage("claudeCommit.xml")])
class ClaudeCommitSettings : SimplePersistentStateComponent<ClaudeCommitSettings.Settings>(Settings()) {

    class Settings : BaseState() {
        /** Empty = the CLI's default model. */
        var model by string("")
        var language by string(DEFAULT_LANGUAGE)
        var timeoutSec by property(DEFAULT_TIMEOUT_SEC)
        /** If false, asks for confirmation before replacing a message that was already typed. */
        var overwriteWithoutAsking by property(false)
        /** Sends the titles of the latest commits so Claude can match the repo's style. */
        var imitateRepoStyle by property(true)
    }

    val model: String get() = state.model?.trim().orEmpty()
    val language: String get() = state.language?.trim()?.ifBlank { null } ?: DEFAULT_LANGUAGE
    val timeoutSec: Long get() = state.timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC).toLong()

    override fun loadState(state: Settings) {
        // Versions up to 1.2.0 stored the language name in Spanish.
        state.language = state.language?.trim()?.let { LEGACY_LANGUAGES[it.lowercase()] ?: it }
        super.loadState(state)
    }

    companion object {
        /** Visible plugin name; must match `<name>`, the notification group and the configurable in plugin.xml. */
        const val PLUGIN_NAME = "Commit Messages for Claude Code"
        const val DEFAULT_LANGUAGE = "English"
        const val DEFAULT_TIMEOUT_SEC = 180
        const val MIN_TIMEOUT_SEC = 10
        const val MAX_TIMEOUT_SEC = 900

        internal val LEGACY_LANGUAGES = mapOf(
            "español" to "Spanish",
            "inglés" to "English",
            "portugués" to "Portuguese",
            "francés" to "French",
            "alemán" to "German",
            "italiano" to "Italian",
        )

        fun getInstance(): ClaudeCommitSettings = service()
    }
}
