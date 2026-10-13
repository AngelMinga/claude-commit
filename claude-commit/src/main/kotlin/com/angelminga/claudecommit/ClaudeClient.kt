package com.angelminga.claudecommit

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.io.IOException

/** Calls the `claude -p` CLI (uses your subscription's session, no API key needed). */
object ClaudeClient {

    // Number of recent titles sent as a style reference, and the maximum length of each one.
    const val RECENT_COMMITS = 20
    private const val MAX_SUBJECT_CHARS = 120

    // Multi-line variable parts are joined outside trimIndent so they don't break its indentation handling.
    internal fun prompt(
        branch: String,
        language: String,
        recentCommits: List<String> = emptyList(),
        alwaysIncludeScope: Boolean = false,
    ): String {
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

        val scope = if (alwaysIncludeScope) "<scope>" else "<optional scope>"
        val format = """
            Reply EXACTLY in this format, without code blocks:
            TYPE: <type>
            REASON: <one sentence explaining why it is that type>
            MESSAGE:
            <type>($scope): <title in the imperative mood, max. 72 characters>

            <optional body with short bullet points, only if it adds value>
        """.trimIndent()

        return listOfNotNull(intro, styleSection(recentCommits, language), scopeRule(alwaysIncludeScope), format)
            .joinToString("\n\n")
    }

    /** Extra rule that makes the scope mandatory; null when the scope stays optional. */
    internal fun scopeRule(alwaysIncludeScope: Boolean): String? {
        if (!alwaysIncludeScope) return null
        return "Always include a scope in the title: a short lowercase word naming the area of the code that changed " +
            "(module, package, component or feature, e.g. settings, client, ui). Reuse a scope from the recent commits " +
            "when one fits. This rule takes precedence over the style of recent commits."
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
        val prompt = prompt(branch, settings.language, recentCommits, settings.state.alwaysIncludeScope)
        val invocation = if (SystemInfo.isWindows) {
            val claude = findWindowsClaude(System.getenv()) { File(it).isFile }
                ?: throw IllegalStateException(ClaudeCommitBundle.message("error.cli.not.found"))
            windowsInvocation(claude, prompt, diff, settings.model)
        } else {
            unixInvocation(defaultShell(), prompt, diff, settings.model)
        }

        val res = try {
            Shell.run(invocation.command, neutralDir(), invocation.stdin, invocation.env, settings.timeoutSec, indicator)
        } catch (e: IOException) {
            throw IllegalStateException(ClaudeCommitBundle.message("error.cli.start.failed", invocation.command.first(), e.message ?: e))
        }

        errorMessage(res)?.let { throw IllegalStateException(it) }
        return CommitMessageParser.parse(res.stdout)
    }

    /** How to run the CLI: command line, stdin and extra environment variables. */
    internal data class Invocation(val command: List<String>, val stdin: String, val env: Map<String, String> = emptyMap())

    /**
     * CLI arguments for the Windows invocation.
     * --tools= (no tools): classifying the diff doesn't require reading files or running anything. Written as
     * `--tools=` rather than `--tools ""` because an empty quoted argument makes `cmd.exe /c` strip quotes and break
     * paths with spaces.
     * --no-session-persistence: doesn't leave a session in the history for every commit.
     */
    internal fun claudeArgs(model: String): List<String> =
        listOf("-p", "--tools=", "--no-session-persistence") + (if (model.isNotBlank()) listOf("--model", model) else emptyList())

    /**
     * macOS/Linux: runs `claude` through a login shell so it finds the user's PATH. IntelliJ launched from the Dock
     * doesn't inherit the terminal's PATH, so common install locations are prepended too. The prompt and the model are
     * passed via environment variables to avoid interpolating user text into the script; the model is added with
     * `set --` because zsh doesn't split `${VAR:+--model "$VAR"}` into two arguments. The diff goes through stdin.
     */
    internal fun unixInvocation(shell: String, prompt: String, diff: String, model: String): Invocation {
        val script = "export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "if [ -n \"\$CLAUDE_COMMIT_MODEL\" ]; then set -- --model \"\$CLAUDE_COMMIT_MODEL\"; else set --; fi; " +
            "claude -p \"\$CLAUDE_COMMIT_PROMPT\" --tools \"\" --no-session-persistence \"\$@\""
        return Invocation(
            command = listOf(shell, "-l", "-c", script),
            stdin = diff,
            env = mapOf("CLAUDE_COMMIT_PROMPT" to prompt, "CLAUDE_COMMIT_MODEL" to model),
        )
    }

    /**
     * Windows (experimental): runs the CLI without a shell. The prompt and the diff both go through stdin, because
     * `cmd.exe` would mangle a multi-line argument with quotes. `.cmd`/`.bat` shims (npm installs) need `cmd.exe /c`.
     */
    internal fun windowsInvocation(claude: String, prompt: String, diff: String, model: String): Invocation {
        val launcher = if (claude.endsWith(".cmd", ignoreCase = true) || claude.endsWith(".bat", ignoreCase = true)) {
            listOf("cmd.exe", "/c", claude)
        } else {
            listOf(claude)
        }
        return Invocation(command = launcher + claudeArgs(model), stdin = "$prompt\n\nDiff:\n$diff")
    }

    /**
     * Finds the Claude Code CLI on Windows: first in PATH (native `claude.exe`, then npm's `claude.cmd`), then in the
     * default locations of the native installer and npm. [exists] is injected so the lookup can be tested on any OS.
     */
    internal fun findWindowsClaude(env: Map<String, String>, exists: (String) -> Boolean): String? {
        fun join(dir: String, name: String) = dir.trimEnd('\\', '/') + "\\" + name
        val pathDirs = (env["PATH"] ?: env["Path"]).orEmpty().split(';').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        val fromPath = listOf("claude.exe", "claude.cmd", "claude.bat").flatMap { name -> pathDirs.map { join(it, name) } }
        val defaults = listOfNotNull(
            env["USERPROFILE"]?.let { join(join(it, ".local\\bin"), "claude.exe") },
            env["APPDATA"]?.let { join(join(it, "npm"), "claude.cmd") },
        )
        return (fromPath + defaults).firstOrNull(exists)
    }

    /** `$SHELL`, or the first available shell if it isn't set (e.g. some Linux desktop launchers). */
    private fun defaultShell(): String =
        System.getenv("SHELL")?.takeIf { it.isNotBlank() }
            ?: listOf("/bin/zsh", "/bin/bash", "/bin/sh").firstOrNull { File(it).canExecute() }
            ?: "/bin/sh"

    /** Turns a failed CLI result into an actionable, localized message; null if it succeeded. */
    internal fun errorMessage(res: ShellResult): String? {
        val output = res.stderr + "\n" + res.stdout
        return when {
            // 127: POSIX shell "command not found"; 9009: cmd.exe "is not recognized as a command".
            res.exitCode == 127 || res.exitCode == 9009 || (res.exitCode != 0 && res.stderr.contains("command not found")) ->
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
