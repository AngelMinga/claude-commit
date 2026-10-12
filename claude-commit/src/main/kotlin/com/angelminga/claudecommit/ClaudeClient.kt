package com.angelminga.claudecommit

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

    fun suggest(diff: String, branch: String, workDir: File, indicator: ProgressIndicator): CommitSuggestion {
        val settings = ClaudeCommitSettings.getInstance()
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        // IntelliJ abierto desde el Dock no hereda el PATH de la terminal: lo completamos.
        // El modelo va por variable de entorno para no interpolar texto del usuario en el script.
        val script = "export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "claude -p \"\$CLAUDE_COMMIT_PROMPT\" \${CLAUDE_COMMIT_MODEL:+--model \"\$CLAUDE_COMMIT_MODEL\"}"

        val res = Shell.run(
            command = listOf(shell, "-l", "-c", script),
            dir = workDir,
            stdin = diff,
            env = mapOf(
                "CLAUDE_COMMIT_PROMPT" to prompt(branch, settings.language),
                "CLAUDE_COMMIT_MODEL" to settings.model,
            ),
            timeoutSec = settings.timeoutSec,
            indicator = indicator,
        )

        if (res.exitCode == 127 || res.stderr.contains("command not found")) {
            throw IllegalStateException("No se encontró la CLI 'claude'. Instala Claude Code y ejecuta 'claude' una vez para iniciar sesión.")
        }
        if (res.exitCode != 0 || res.stdout.isBlank()) {
            val detail = res.stderr.ifBlank { res.stdout }.take(500)
            throw IllegalStateException("Claude falló (código ${res.exitCode}): $detail")
        }
        return CommitMessageParser.parse(res.stdout)
    }
}
