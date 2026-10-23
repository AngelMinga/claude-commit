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
        extraInstructions: String = "",
        previousSuggestion: String? = null,
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

        return listOfNotNull(
            intro,
            styleSection(recentCommits, language),
            scopeRule(alwaysIncludeScope),
            extraInstructionsSection(extraInstructions),
            retrySection(previousSuggestion),
            format,
        ).joinToString("\n\n")
    }

    /** The user's own instructions from the settings; null if there are none. */
    internal fun extraInstructionsSection(extraInstructions: String): String? {
        if (extraInstructions.isBlank()) return null
        return "Additional instructions from the user (follow them unless they conflict with the reply format below):\n" +
            extraInstructions.trim()
    }

    /** Asks for a different message when the user clicked "Retry"; null on the first attempt. */
    internal fun retrySection(previousSuggestion: String?): String? {
        if (previousSuggestion.isNullOrBlank()) return null
        return "The user rejected this previous suggestion and wants a different one. Write a new message with " +
            "different wording; change the type or scope only if a better one fits.\n" + previousSuggestion.trim()
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

    /**
     * Set when the installed CLI rejects a fast mode option: fast mode is skipped (and the user told once) for the rest
     * of the IDE session. Reset on restart, so updating Claude Code is picked up.
     */
    @Volatile
    private var fastModeUnsupported = false

    fun suggest(
        diff: String,
        branch: String,
        recentCommits: List<String>,
        previousSuggestion: String?,
        projectCommitRules: String?,
        indicator: ProgressIndicator,
        onFastModeUnsupported: () -> Unit,
        onPartialText: (String) -> Unit = {},
    ): CommitSuggestion {
        val settings = ClaudeCommitSettings.getInstance()
        if (settings.state.fastMode && !fastModeUnsupported) {
            val res = runFast(settings, diff, branch, recentCommits, previousSuggestion, projectCommitRules, indicator, onPartialText)
            if (!isUnsupportedOption(res)) return parseOrThrow(res)
            // Older CLI: fall back to the normal command, now and for the rest of the session.
            fastModeUnsupported = true
            onFastModeUnsupported()
        }
        return parseOrThrow(runNormal(settings, diff, branch, recentCommits, previousSuggestion, indicator))
    }

    /** Normal mode: the original command, with the whole prompt as an argument (Unix) and the diff through stdin. */
    private fun runNormal(
        settings: ClaudeCommitSettings,
        diff: String,
        branch: String,
        recentCommits: List<String>,
        previousSuggestion: String?,
        indicator: ProgressIndicator,
    ): ShellResult {
        val prompt = prompt(
            branch,
            settings.language,
            recentCommits,
            settings.state.alwaysIncludeScope,
            settings.extraInstructions,
            previousSuggestion,
        )
        val invocation = if (SystemInfo.isWindows) {
            windowsInvocation(windowsClaude(settings), prompt, diff, settings.model, settings.effort)
        } else {
            unixInvocation(defaultShell(), prompt, diff, settings.model, settings.effort, unixClaude(settings))
        }
        return run(invocation, settings, indicator)
    }

    /**
     * Fast mode: the rules replace Claude Code's default system prompt (much shorter, so the first token comes sooner)
     * and MCP servers are skipped; the data (branch, recent titles, rejected suggestion, diff) goes through stdin.
     */
    private fun runFast(
        settings: ClaudeCommitSettings,
        diff: String,
        branch: String,
        recentCommits: List<String>,
        previousSuggestion: String?,
        projectCommitRules: String?,
        indicator: ProgressIndicator,
        onPartialText: (String) -> Unit,
    ): ShellResult {
        val system = systemPrompt(
            settings.language,
            hasRecentCommits = recentCommits.isNotEmpty(),
            alwaysIncludeScope = settings.state.alwaysIncludeScope,
            extraInstructions = settings.extraInstructions,
            isRetry = !previousSuggestion.isNullOrBlank(),
            projectCommitRules = projectCommitRules,
        )
        val user = userMessage(branch, recentCommits, previousSuggestion, diff)
        // Streamed reply: text fragments are accumulated and reported as they arrive.
        val partial = StringBuilder()
        val onLine = { line: String ->
            StreamJson.textDelta(line)?.let { fragment -> onPartialText(partial.append(fragment).toString()) }
            Unit
        }
        val raw = if (!SystemInfo.isWindows) {
            run(unixFastInvocation(defaultShell(), system, user, settings.model, settings.effort, unixClaude(settings)), settings, indicator, onLine)
        } else {
            // cmd.exe can't pass a multi-line argument, so on Windows the system prompt goes through a temporary file.
            val file = File.createTempFile("system-prompt", ".txt", neutralDir())
            try {
                file.writeText(system, Charsets.UTF_8)
                run(windowsFastInvocation(windowsClaude(settings), file.path, user, settings.model, settings.effort), settings, indicator, onLine)
            } finally {
                file.delete()
            }
        }
        return fromStream(raw, partial.toString())
    }

    /**
     * Turns a stream-json run into a plain result, so the parser, [errorMessage] and [isUnsupportedOption] work as in
     * normal mode: stdout becomes the final `result` text (or the accumulated fragments if it's missing), and a result
     * flagged `is_error` (e.g. "Not logged in · Please run /login") never counts as a success. stderr is kept as is.
     */
    internal fun fromStream(raw: ShellResult, accumulated: String): ShellResult {
        val result = raw.stdout.lineSequence().mapNotNull(StreamJson::result).lastOrNull()
        val text = result?.text ?: accumulated
        val exitCode = if (result?.isError == true && raw.exitCode == 0) 1 else raw.exitCode
        return ShellResult(exitCode, text, raw.stderr)
    }

    private fun run(
        invocation: Invocation,
        settings: ClaudeCommitSettings,
        indicator: ProgressIndicator,
        onStdoutLine: ((String) -> Unit)? = null,
    ): ShellResult =
        try {
            Shell.run(invocation.command, neutralDir(), invocation.stdin, invocation.env, settings.timeoutSec, indicator, onStdoutLine)
        } catch (e: IOException) {
            throw IllegalStateException(ClaudeCommitBundle.message("error.cli.start.failed", invocation.command.first(), e.message ?: e))
        }

    private fun parseOrThrow(res: ShellResult): CommitSuggestion {
        errorMessage(res)?.let { throw IllegalStateException(it) }
        return CommitMessageParser.parse(res.stdout)
    }

    private fun windowsClaude(settings: ClaudeCommitSettings): String =
        configuredClaude(settings)
            ?: findWindowsClaude(System.getenv()) { File(it).isFile }
            ?: throw IllegalStateException(ClaudeCommitBundle.message("error.cli.not.found"))

    /** On macOS/Linux an empty result means "find `claude` through the login shell's PATH". */
    private fun unixClaude(settings: ClaudeCommitSettings): String = configuredClaude(settings).orEmpty()

    /** The executable set in the settings (with `~` expanded), or null if none; fails clearly if it doesn't exist. */
    private fun configuredClaude(settings: ClaudeCommitSettings): String? {
        val raw = settings.claudePath.ifBlank { return null }
        val path = expandHome(raw, System.getProperty("user.home"))
        if (!File(path).isFile) throw IllegalStateException(ClaudeCommitBundle.message("error.cli.path.invalid", raw))
        return path
    }

    internal fun expandHome(path: String, home: String): String =
        if (path == "~" || path.startsWith("~/") || path.startsWith("~\\")) home + path.substring(1) else path

    /** The CLI rejected an option (e.g. `error: unknown option '--strict-mcp-config'` on an older Claude Code). */
    internal fun isUnsupportedOption(res: ShellResult): Boolean =
        res.exitCode != 0 && UNSUPPORTED_OPTION.containsMatchIn(res.stderr + "\n" + res.stdout)

    private val UNKNOWN_OPTION_NAME = Regex("""(--[\w-]+)""")

    private val UNSUPPORTED_OPTION =
        Regex("""unknown option|unknown argument|unrecognized (option|argument)|unexpected argument""", RegexOption.IGNORE_CASE)

    /**
     * Fast mode system prompt: the same rules as [prompt], worded for data that arrives in the user message.
     * [projectCommitRules] is the commit section of the project's CLAUDE.md, if any.
     */
    internal fun systemPrompt(
        language: String,
        hasRecentCommits: Boolean = false,
        alwaysIncludeScope: Boolean = false,
        extraInstructions: String = "",
        isRetry: Boolean = false,
        projectCommitRules: String? = null,
    ): String {
        val intro = """
            You write Git commit messages. The user message contains the current branch, optionally the titles of the latest commits and a previous suggestion the user rejected, and then the diff.

            Classify the change in the diff according to Conventional Commits.

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

            If the branch name contains a ticket ID (e.g. ABC-123), include it in the title.

            Write the reason, the title and the body in $language. The TYPE, REASON and MESSAGE labels are not translated.
        """.trimIndent()

        val style = if (hasRecentCommits) {
            "Match the style of the latest commit titles listed in the user message " +
                "(scopes in use, capitalization, length, ticket ID format), but always write in $language " +
                "and follow the requested Conventional Commits format. They are only a reference: do not copy their content."
        } else {
            null
        }
        val retry = if (isRetry) {
            "The user rejected the previous suggestion included in the user message and wants a different one. Write a new " +
                "message with different wording; change the type or scope only if a better one fits."
        } else {
            null
        }
        val projectRules = projectCommitRules?.takeIf { it.isNotBlank() }?.let {
            "Commit conventions from the project's CLAUDE.md (follow them unless they conflict with the reply format below):\n" +
                it.trim()
        }
        val scope = if (alwaysIncludeScope) "<scope>" else "<optional scope>"
        val format = """
            Reply EXACTLY in this format, without code blocks:
            TYPE: <type>
            REASON: <one sentence explaining why it is that type>
            MESSAGE:
            <type>($scope): <title in the imperative mood, max. 72 characters>

            <optional body with short bullet points, only if it adds value>
        """.trimIndent()

        return listOfNotNull(
            intro,
            style,
            scopeRule(alwaysIncludeScope),
            projectRules,
            extraInstructionsSection(extraInstructions),
            retry,
            format,
        ).joinToString("\n\n")
    }

    /** Fast mode user message (sent through stdin): only data, no rules. */
    internal fun userMessage(branch: String, recentCommits: List<String>, previousSuggestion: String?, diff: String): String =
        listOfNotNull(
            "Current branch: $branch",
            recentCommits.takeIf { it.isNotEmpty() }?.let { titles ->
                "Titles of the latest commits, newest first:\n" + titles.joinToString("\n") { "- $it" }
            },
            previousSuggestion?.takeIf { it.isNotBlank() }?.let { "Rejected previous suggestion:\n${it.trim()}" },
            "Diff:\n$diff",
        ).joinToString("\n\n")

    /**
     * Commit-related section of a CLAUDE.md: the first heading that mentions "commit" and everything until the next
     * heading of the same or a higher level (headings inside code fences are ignored). Null if there is none.
     */
    internal fun commitSection(markdown: String, maxChars: Int = MAX_PROJECT_RULES_CHARS): String? {
        val headings = mutableListOf<Pair<Int, Int>>() // line index to heading level
        var inFence = false
        val lines = markdown.lines()
        lines.forEachIndexed { i, line ->
            if (line.trimStart().startsWith("```")) inFence = !inFence
            if (!inFence) HEADING.find(line)?.let { headings += i to it.groupValues[1].length }
        }
        val start = headings.firstOrNull { (i, _) -> COMMIT_WORD.containsMatchIn(lines[i]) } ?: return null
        val end = headings.firstOrNull { (i, level) -> i > start.first && level <= start.second }?.first ?: lines.size
        val section = lines.subList(start.first, end).joinToString("\n").trim()
        return if (section.length > maxChars) section.take(maxChars) + "…" else section
    }

    private const val MAX_PROJECT_RULES_CHARS = 4000
    private val HEADING = Regex("""^(#{1,6})\s+\S""")
    private val COMMIT_WORD = Regex("""\bcommit""", RegexOption.IGNORE_CASE)

    /** How to run the CLI: command line, stdin and extra environment variables. */
    internal data class Invocation(val command: List<String>, val stdin: String, val env: Map<String, String> = emptyMap())

    /**
     * CLI arguments for the Windows invocation.
     * --tools= (no tools): classifying the diff doesn't require reading files or running anything. Written as
     * `--tools=` rather than `--tools ""` because an empty quoted argument makes `cmd.exe /c` strip quotes and break
     * paths with spaces.
     * --no-session-persistence: doesn't leave a session in the history for every commit.
     */
    internal fun claudeArgs(model: String, effort: String = ""): List<String> =
        listOf("-p", "--tools=", "--no-session-persistence") + modelAndEffortArgs(model, effort)

    private fun modelAndEffortArgs(model: String, effort: String): List<String> =
        (if (model.isNotBlank()) listOf("--model", model) else emptyList()) +
            (if (effort.isNotBlank()) listOf("--effort", effort) else emptyList())

    /**
     * Shared start of the macOS/Linux scripts: PATH, then `--model` and (only if set) `--effort` added to `"$@"`
     * with `set --`. With no effort and no custom path the script is exactly the original one.
     */
    private fun unixPrelude(effort: String): String =
        "export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
            "if [ -n \"\$CLAUDE_COMMIT_MODEL\" ]; then set -- --model \"\$CLAUDE_COMMIT_MODEL\"; else set --; fi; " +
            (if (effort.isNotBlank()) "set -- \"\$@\" --effort \"\$CLAUDE_COMMIT_EFFORT\"; " else "")

    /** `claude` from PATH, or the configured executable (passed through an environment variable, never interpolated). */
    private fun unixCommand(claudePath: String): String = if (claudePath.isBlank()) "claude" else "\"\$CLAUDE_COMMIT_BIN\""

    private fun unixExtraEnv(effort: String, claudePath: String): Map<String, String> =
        (if (effort.isNotBlank()) mapOf("CLAUDE_COMMIT_EFFORT" to effort) else emptyMap()) +
            (if (claudePath.isNotBlank()) mapOf("CLAUDE_COMMIT_BIN" to claudePath) else emptyMap())

    /**
     * macOS/Linux: runs `claude` through a login shell so it finds the user's PATH. IntelliJ launched from the Dock
     * doesn't inherit the terminal's PATH, so common install locations are prepended too. The prompt and the model are
     * passed via environment variables to avoid interpolating user text into the script; the model is added with
     * `set --` because zsh doesn't split `${VAR:+--model "$VAR"}` into two arguments. The diff goes through stdin.
     */
    internal fun unixInvocation(
        shell: String,
        prompt: String,
        diff: String,
        model: String,
        effort: String = "",
        claudePath: String = "",
    ): Invocation {
        val script = unixPrelude(effort) +
            "${unixCommand(claudePath)} -p \"\$CLAUDE_COMMIT_PROMPT\" --tools \"\" --no-session-persistence \"\$@\""
        return Invocation(
            command = listOf(shell, "-l", "-c", script),
            stdin = diff,
            env = mapOf("CLAUDE_COMMIT_PROMPT" to prompt, "CLAUDE_COMMIT_MODEL" to model) + unixExtraEnv(effort, claudePath),
        )
    }

    /**
     * Windows (experimental): runs the CLI without a shell. The prompt and the diff both go through stdin, because
     * `cmd.exe` would mangle a multi-line argument with quotes. `.cmd`/`.bat` shims (npm installs) need `cmd.exe /c`.
     */
    internal fun windowsInvocation(claude: String, prompt: String, diff: String, model: String, effort: String = ""): Invocation {
        return Invocation(command = windowsLauncher(claude) + claudeArgs(model, effort), stdin = "$prompt\n\nDiff:\n$diff")
    }

    private fun windowsLauncher(claude: String): List<String> =
        if (claude.endsWith(".cmd", ignoreCase = true) || claude.endsWith(".bat", ignoreCase = true)) {
            listOf("cmd.exe", "/c", claude)
        } else {
            listOf(claude)
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

    /**
     * macOS/Linux fast mode: same login shell, PATH and model handling as [unixInvocation]. The system prompt goes
     * through an environment variable and the user message through stdin. No `--bare` (it breaks subscription login)
     * and no `--setting-sources`.
     */
    internal fun unixFastInvocation(
        shell: String,
        systemPrompt: String,
        userMessage: String,
        model: String,
        effort: String = "",
        claudePath: String = "",
    ): Invocation {
        val script = unixPrelude(effort) +
            "${unixCommand(claudePath)} -p \"\$@\" --system-prompt \"\$CLAUDE_COMMIT_SYSTEM_PROMPT\" --tools \"\" --strict-mcp-config --no-session-persistence " +
            STREAM_ARGS.joinToString(" ")
        return Invocation(
            command = listOf(shell, "-l", "-c", script),
            stdin = userMessage,
            env = mapOf("CLAUDE_COMMIT_SYSTEM_PROMPT" to systemPrompt, "CLAUDE_COMMIT_MODEL" to model) + unixExtraEnv(effort, claudePath),
        )
    }

    /** Windows fast mode (experimental): the system prompt is read from [systemPromptFile]; no quoted or empty args. */
    internal fun windowsFastInvocation(
        claude: String,
        systemPromptFile: String,
        userMessage: String,
        model: String,
        effort: String = "",
    ): Invocation {
        val args = listOf("-p") + modelAndEffortArgs(model, effort) +
            listOf("--system-prompt-file", systemPromptFile, "--tools=", "--strict-mcp-config", "--no-session-persistence") +
            STREAM_ARGS
        return Invocation(command = windowsLauncher(claude) + args, stdin = userMessage)
    }

    /** Fast mode reply as stream-json events with partial text (`--verbose` is required by stream-json in print mode). */
    private val STREAM_ARGS = listOf("--output-format", "stream-json", "--verbose", "--include-partial-messages")

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
            res.exitCode != 0 && UNSUPPORTED_OPTION.containsMatchIn(output) ->
                ClaudeCommitBundle.message("error.cli.option.unsupported", UNKNOWN_OPTION_NAME.find(output)?.groupValues?.get(1) ?: "?")
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
