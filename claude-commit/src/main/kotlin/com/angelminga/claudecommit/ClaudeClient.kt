package com.angelminga.claudecommit

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File

/** Llama a la CLI `claude -p` (usa la sesión de tu suscripción, sin API key). */
object ClaudeClient {

    private fun prompt(branch: String, language: String) = """
        Analiza el siguiente diff y clasifica el cambio según Conventional Commits.

        Tipos válidos:
        - feat: nueva funcionalidad para el usuario
        - fix: corrección de un bug
        - refactor: cambio de código que no agrega funcionalidad ni corrige bugs
        - perf: mejora de rendimiento
        - style: formato, espacios, punto y coma (sin cambio de lógica)
        - docs: solo documentación
        - test: agrega o corrige tests
        - build: sistema de build o dependencias (package.json, gradle, pubspec…)
        - ci: configuración de CI/CD
        - chore: tareas de mantenimiento que no tocan código fuente ni tests
        - revert: revierte un commit anterior

        Rama actual: $branch. Si el nombre de la rama contiene un ID de ticket (ej. ABC-123), inclúyelo en el título.

        Escribe la razón, el título y el cuerpo en $language. Las etiquetas TIPO, RAZON y MENSAJE no se traducen.

        Responde EXACTAMENTE con este formato, sin bloques de código:
        TIPO: <tipo>
        RAZON: <una frase explicando por qué es ese tipo>
        MENSAJE:
        <tipo>(<scope opcional>): <título en imperativo, máx. 72 caracteres>

        <cuerpo opcional con viñetas breves, solo si aporta>
    """.trimIndent()

    fun suggest(diff: String, branch: String, indicator: ProgressIndicator): CommitSuggestion {
        val settings = ClaudeCommitSettings.getInstance()
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        // IntelliJ abierto desde el Dock no hereda el PATH de la terminal: lo completamos.
        // El modelo va por variable de entorno para no interpolar texto del usuario en el script. Se añade con
        // `set --` porque zsh no divide `${VAR:+--model "$VAR"}` en dos argumentos.
        // --tools "": para clasificar el diff no necesita leer archivos ni ejecutar nada.
        // --no-session-persistence: no deja una sesión en el historial por cada commit.
        val script = "export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "if [ -n \"\$CLAUDE_COMMIT_MODEL\" ]; then set -- --model \"\$CLAUDE_COMMIT_MODEL\"; else set --; fi; " +
            "claude -p \"\$CLAUDE_COMMIT_PROMPT\" --tools \"\" --no-session-persistence \"\$@\""

        val res = Shell.run(
            command = listOf(shell, "-l", "-c", script),
            dir = neutralDir(),
            stdin = diff,
            env = mapOf(
                "CLAUDE_COMMIT_PROMPT" to prompt(branch, settings.language),
                "CLAUDE_COMMIT_MODEL" to settings.model,
            ),
            timeoutSec = settings.timeoutSec,
            indicator = indicator,
        )

        errorMessage(res)?.let { throw IllegalStateException(it) }
        return CommitMessageParser.parse(res.stdout)
    }

    /** Traduce un resultado fallido de la CLI a un mensaje accionable; null si fue bien. */
    internal fun errorMessage(res: ShellResult): String? {
        val output = res.stderr + "\n" + res.stdout
        return when {
            res.exitCode == 127 || (res.exitCode != 0 && res.stderr.contains("command not found")) ->
                "No se encontró la CLI 'claude'. Instala Claude Code y ejecuta 'claude' una vez para iniciar sesión."
            res.exitCode != 0 && LOGIN_ERRORS.any { output.contains(it, ignoreCase = true) } ->
                "No has iniciado sesión en Claude Code o la sesión caducó. Abre una terminal, ejecuta 'claude' " +
                    "y elige iniciar sesión con tu suscripción; luego vuelve a pulsar el botón."
            res.exitCode != 0 || res.stdout.isBlank() ->
                "Claude falló (código ${res.exitCode}): ${res.stderr.ifBlank { res.stdout }.take(500)}"
            else -> null
        }
    }

    // Textos con los que la CLI indica que falta sesión o que caducó (p. ej. "Not logged in · Please run /login").
    private val LOGIN_ERRORS = listOf("not logged in", "please run /login", "invalid api key", "token has expired", "authentication_error")

    /**
     * Directorio fuera del repo: dentro del proyecto la CLI cargaría su CLAUDE.md, hooks y servidores MCP,
     * lo que la ralentiza y puede alterar el formato de la respuesta. Todo lo que necesita va en el diff y el prompt.
     */
    private fun neutralDir(): File = File(PathManager.getTempPath(), "claude-commit").apply { mkdirs() }
}
