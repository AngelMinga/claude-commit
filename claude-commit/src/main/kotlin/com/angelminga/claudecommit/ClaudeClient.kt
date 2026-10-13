package com.angelminga.claudecommit

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import java.io.File

/** Calls the `claude -p` CLI (uses your subscription's session, no API key needed). */
object ClaudeClient {

    // Number of recent titles sent as a style reference, and the maximum length of each one.
    const val RECENT_COMMITS = 20
    private const val MAX_SUBJECT_CHARS = 120

    // Multi-line variable parts are joined outside trimIndent so they don't break its indentation handling.
    internal fun prompt(branch: String, language: String, recentCommits: List<String> = emptyList()): String {
        val intro = """
            Analyze the following diff and classify the change according to Conventional Commits.

            Valid types:
            - feat: a new feature for the user
            - fix: a bug fix
            - refactor: a code change that neither adds a feature nor fixes a bug
            - perf: a performance improvement
            - style: formatting, whitespace, semicolons (no logic change)
            - docs: documentation only
            - test: adds or fixes tests
            - build: build system or dependencies (package.json, gradle, pubspec…)
            - ci: CI/CD configuration
            - chore: maintenance tasks that don't touch source code or tests
            - revert: reverts a previous commit

            Current branch: $branch. If the branch name contains a ticket ID (e.g. ABC-123), include it in the title.

            Write the reason, the title and the body in $language. The TYPE, REASON and MESSAGE labels are not translated.
        """.trimIndent()

        val format = """
            Reply EXACTLY in this format, without code blocks:
            TYPE: <type>
            REASON: <one sentence explaining why it is that type>
            MESSAGE:
            <type>(<optional scope>): <title in the imperative mood, max. 72 characters>

            <optional body with short bullet points, only if it adds value>
        """.trimIndent()

        return listOfNotNull(intro, styleSection(recentCommits, language), format).joinToString("\n\n")
    }

    /** Recent titles of the repo so Claude can match its style; null if there are none. */
    internal fun styleSection(recentCommits: List<String>, language: String): String? {
        if (recentCommits.isEmpty()) return null
        return "Titles of the latest commits in the repository, newest first. Match their style " +
            "(scopes in use, capitalization, length, ticket ID format), but always write in $language " +
            "and follow the requested Conventional Commits format. They are only a reference: do not copy their content.\n" +
            recentCommits.joinToString("\n") { "- $it" }
    }

    /** Turns the output of `git log --format=%s` into a clean list of titles. */
    internal fun parseRecentCommits(gitLogOutput: String): List<String> =
        gitLogOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.length > MAX_SUBJECT_CHARS) it.take(MAX_SUBJECT_CHARS) + "…" else it }
            .take(RECENT_COMMITS)
            .toList()

    fun suggest(diff: String, branch: String, recentCommits: List<String>, indicator: ProgressIndicator): CommitSuggestion {
        val settings = ClaudeCommitSettings.getInstance()
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        // IntelliJ launched from the Dock doesn't inherit the terminal's PATH, so we extend it.
        // The model is passed via an environment variable to avoid interpolating user text into the script. It is
        // added with `set --` because zsh doesn't split `${VAR:+--model "$VAR"}` into two arguments.
        // --tools "": classifying the diff doesn't require reading files or running anything.
        // --no-session-persistence: doesn't leave a session in the history for every commit.
        val script = "export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "if [ -n \"\$CLAUDE_COMMIT_MODEL\" ]; then set -- --model \"\$CLAUDE_COMMIT_MODEL\"; else set --; fi; " +
            "claude -p \"\$CLAUDE_COMMIT_PROMPT\" --tools \"\" --no-session-persistence \"\$@\""

        val res = Shell.run(
            command = listOf(shell, "-l", "-c", script),
            dir = neutralDir(),
            stdin = diff,
            env = mapOf(
                "CLAUDE_COMMIT_PROMPT" to prompt(branch, settings.language, recentCommits),
                "CLAUDE_COMMIT_MODEL" to settings.model,
            ),
            timeoutSec = settings.timeoutSec,
            indicator = indicator,
        )

        errorMessage(res)?.let { throw IllegalStateException(it) }
        return CommitMessageParser.parse(res.stdout)
    }

    /** Turns a failed CLI result into an actionable, localized message; null if it succeeded. */
    internal fun errorMessage(res: ShellResult): String? {
        val output = res.stderr + "\n" + res.stdout
        return when {
            res.exitCode == 127 || (res.exitCode != 0 && res.stderr.contains("command not found")) ->
                ClaudeCommitBundle.message("error.cli.not.found")
            res.exitCode != 0 && LOGIN_ERRORS.any { output.contains(it, ignoreCase = true) } ->
                ClaudeCommitBundle.message("error.not.logged.in")
            res.exitCode != 0 || res.stdout.isBlank() ->
                ClaudeCommitBundle.message("error.cli.failed", res.exitCode, res.stderr.ifBlank { res.stdout }.take(500))
            else -> null
        }
    }

    // Texts the CLI uses to report a missing or expired session (e.g. "Not logged in · Please run /login").
    private val LOGIN_ERRORS = listOf("not logged in", "please run /login", "invalid api key", "token has expired", "authentication_error")

    /**
     * Directory outside the repo: inside the project the CLI would load its CLAUDE.md, hooks and MCP servers,
     * which slows it down and can change the response format. Everything it needs is in the diff and the prompt.
     */
    private fun neutralDir(): File = File(PathManager.getTempPath(), "claude-commit").apply { mkdirs() }
}
