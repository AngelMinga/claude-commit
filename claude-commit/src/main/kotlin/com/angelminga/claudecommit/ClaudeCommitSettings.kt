package com.angelminga.claudecommit

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Ajustes globales del plugin (Settings → Tools → Commit Messages for Claude Code). */
@Service(Service.Level.APP)
@State(name = "ClaudeCommitSettings", storages = [Storage("claudeCommit.xml")])
class ClaudeCommitSettings : SimplePersistentStateComponent<ClaudeCommitSettings.Settings>(Settings()) {

    class Settings : BaseState() {
        /** Vacío = modelo por defecto de la CLI. */
        var model by string("")
        var language by string(DEFAULT_LANGUAGE)
        var timeoutSec by property(DEFAULT_TIMEOUT_SEC)
        /** Si es false, pide confirmación antes de reemplazar un mensaje ya escrito. */
        var overwriteWithoutAsking by property(false)
    }

    val model: String get() = state.model?.trim().orEmpty()
    val language: String get() = state.language?.trim()?.ifBlank { null } ?: DEFAULT_LANGUAGE
    val timeoutSec: Long get() = state.timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC).toLong()

    companion object {
        /** Nombre visible del plugin; debe coincidir con `<name>`, el grupo de notificaciones y el configurable de plugin.xml. */
        const val PLUGIN_NAME = "Commit Messages for Claude Code"
        const val DEFAULT_LANGUAGE = "español"
        const val DEFAULT_TIMEOUT_SEC = 180
        const val MIN_TIMEOUT_SEC = 10
        const val MAX_TIMEOUT_SEC = 900

        fun getInstance(): ClaudeCommitSettings = service()
    }
}
