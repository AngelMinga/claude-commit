# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What it is

A JetBrains IDE plugin (Kotlin, IntelliJ Platform Gradle Plugin 2.x) that adds a button to the commit panel: it sends the diff of the checked files to the `claude -p` CLI (subscription, no API key) and fills in a Conventional Commits message. **The prompt, code comments and documentation are in English**; keep new code consistent. **User-facing text is localized**: never hardcode it, add a key to `messages/ClaudeCommitBundle.properties` (English, default) **and** `ClaudeCommitBundle_es.properties` (Spanish), and read it with `ClaudeCommitBundle.message(...)` (or `bundle`/`key` attributes in `plugin.xml`). The platform picks the file matching the IDE language (Spanish requires a Spanish language pack). Texts with `{0}` go through `MessageFormat`, so use double quotes instead of apostrophes there. Other Spanish left on purpose is backward compatibility: legacy labels accepted by `CommitMessageParser` and `ClaudeCommitSettings.LEGACY_LANGUAGES`.

## Structure

```text
claude-commit/
├── build.gradle.kts                 # IntelliJ Platform Gradle Plugin, target IDE version, sinceBuild, test deps, publishing
├── gradle.properties / settings.gradle.kts
├── gradle/wrapper/                  # only gradle-wrapper.properties (Gradle 8.14.3), no gradlew script
└── src/
    ├── main/kotlin/com/angelminga/claudecommit/
    │   ├── GenerateCommitAction.kt      # commit panel button; orchestrates the whole flow
    │   ├── DiffCollector.kt             # checked changes → unified diff
    │   ├── ClaudeClient.kt              # prompt + running `claude -p` + CLI error messages
    │   ├── CommitMessageParser.kt       # Claude's reply → CommitSuggestion (no IntelliJ deps)
    │   ├── Shell.kt                     # process execution with timeout and cancellation
    │   ├── ClaudeCommitBundle.kt        # UI texts (ResourceBundle in the IDE language)
    │   ├── ClaudeCommitSettings.kt      # persistent state (model, language, timeout, style, overwrite)
    │   └── ClaudeCommitConfigurable.kt  # UI for Settings → Tools → Commit Messages for Claude Code
    ├── main/resources/
    │   ├── messages/ClaudeCommitBundle.properties / _es.properties  # UI texts (English default, Spanish)
    │   ├── META-INF/plugin.xml                    # id, dependencies, action, notifications, settings, description, change notes
    │   ├── META-INF/claudeCommit-platformVcs.xml  # empty descriptor for the optional dependency (see below)
    │   ├── META-INF/pluginIcon.svg / pluginIcon_dark.svg  # 40×40 icon for the plugin list (light/dark)
    │   └── icons/claudeCommit.svg / _dark.svg     # 16×16 button icon (same design as pluginIcon)
    └── test/kotlin/com/angelminga/claudecommit/
        ├── CommitMessageParserTest.kt
        ├── ClaudeClientErrorTest.kt     # CLI error messages (not logged in, not installed…)
        ├── ClaudeClientFastModeTest.kt  # fast mode command lines, system prompt/user message split, CLAUDE.md commit section
        ├── ClaudeClientInvocationTest.kt  # Unix/Windows command lines and Windows CLI lookup
        ├── ClaudeClientLegacyGoldenTest.kt  # pins the normal-mode prompt and command
        ├── ClaudeClientPromptTest.kt    # prompt, style section and git log parsing
        ├── ClaudeCommitBundleTest.kt    # English and Spanish bundles have the same keys and placeholders
        ├── ClaudeCommitSettingsTest.kt  # IDE/fixed language resolution and migration of legacy Spanish values
        └── ShellTest.kt                 # large stdin, Broken pipe, timeout
```

The repository root is the parent folder (`plugins-intellij/`), which also holds `LICENSE` (MIT) and `PRIVACY.md`.

## Commands

### Build the installable .zip

The repo doesn't include the `gradlew` script. Two options:

```bash
# A) Generate the wrapper once (requires `gradle`, e.g. `brew install gradle`) and use it
gradle wrapper
./gradlew buildPlugin

# B) Without a wrapper: use the Gradle 8.14.3 that IntelliJ already downloaded into the cache
G=$(ls -d ~/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle)
$G clean test buildPlugin
```

Output: `build/distributions/claude-commit-<version>.zip` (the version comes from `version` in `build.gradle.kts`). Install it with *Settings → Plugins → ⚙ → Install Plugin from Disk…* and restart the IDE. To reinstall, uninstall the previous version first or bump `version`.

### Publish to JetBrains Marketplace

The first version was uploaded by hand at plugins.jetbrains.com. For later ones: bump `version` in `build.gradle.kts`, add an entry to `<change-notes>` in `plugin.xml` and run `PUBLISH_TOKEN=<token> ./gradlew publishPlugin` (token from Marketplace → Profile → My Tokens; never commit it). Before publishing, run `./gradlew verifyPluginStructure` and the Plugin Verifier against the target IDEs.

### Other

```bash
./gradlew runIde        # opens a sandbox IntelliJ with the plugin loaded
./gradlew test          # JUnit 4 tests
./gradlew test --tests 'com.angelminga.claudecommit.CommitMessageParserTest'   # a single test class
```

- Requires JDK 21 (`jvmToolchain(21)`). Compiles against IntelliJ IDEA Community 2025.2; `sinceBuild = 252`. VCS dependencies in `plugin.xml`: `com.intellij.modules.vcs` (required; up to 262 VCS lives in the platform core) and `com.intellij.platform.vcs` as optional with an empty `claudeCommit-platformVcs.xml` (since 263 VCS is that plugin and without this dependency its classes aren't visible). Don't remove either one. To check compatibility with a local IDE: `java -jar verifier-cli-<ver>-all.jar check-plugin build/distributions/claude-commit-<version>.zip "<IDE>.app/Contents"`. Compiling against 2026.3 requires IntelliJ Platform Gradle Plugin ≥ 2.19 and Gradle 9.
- `buildSearchableOptions = false` and `kotlin.stdlib.default.dependency=false` (the platform's stdlib is used).
- `bundledModule("intellij.platform.vcs.impl")` is required: `IdeaTextPatchBuilder`, `UnifiedDiffWriter` and the commit workflow classes live in that module and aren't on the default classpath in 2025.2.
- **Don't use `@ApiStatus.Internal` / `@Experimental` / `@ScheduledForRemoval` platform APIs**: JetBrains Marketplace flags them. Several became internal in 2026.3 and were replaced: `AbstractCommitWorkflowHandler`/`CommitWorkflowUi` → `CheckinProjectPanel`; `textListCellRenderer` → `Option.toString()`; extending `DynamicBundle` (its `getMessage` is in the internal `AbstractBundle`) → `ClaudeCommitBundle` loads `ResourceBundle` itself with `DynamicBundle.getLocale()`. Check new APIs in the target IDE jars before using them (`javap -v`, look for `org.jetbrains.annotations.ApiStatus$Internal` in the class or member `RuntimeInvisibleAnnotations`, not in `kotlin.Metadata`).

## Architecture

`GenerateCommitAction` flow (registered in `plugin.xml` under `Vcs.MessageActionGroup`):

1. On the EDT it reads the checked files through the public `CheckinProjectPanel` (`Refreshable.PANEL_KEY`, provided next to `COMMIT_MESSAGE_CONTROL`): `selectedChanges` are the included changes, and the checked unversioned files are the `virtualFiles` that don't belong to a change; without a commit panel it uses the active changelist; with a panel and nothing checked, it warns and stops. It saves open documents (`saveAllDocuments`) and calls `setRunning(project, true)`: the `RUNNING` key disables the button and `update` swaps its icon **and its disabled icon** for `AnimatedIcon.Default` (without `disabledIcon` the disabled button paints a grayed-out static copy, so the spinner looks frozen; `ActivityTracker.inc()` refreshes the toolbar immediately); a `javax.swing.Timer` shows the elapsed seconds in the placeholder (`progress.placeholder.elapsed`) and in the progress indicator's `text2`; the message field (`CommitMessage.editorField`) shows the `progress.placeholder` hint, restored to `VcsBundle` `commit.message.placeholder` in `onFinished`.
2. It starts a `Task.Backgroundable` that:
   - `DiffCollector.collect` → builds a unified diff with `IdeaTextPatchBuilder` + `UnifiedDiffWriter` from IntelliJ's revisions (VCS-agnostic, honors hunks excluded in partial commits). Unversioned files become `Change(null, CurrentContentRevision)`. Binaries → `[binary file]`. Truncated to 15,000 characters per file and 100,000 in total.
   - Reads the branch (`git rev-parse --abbrev-ref HEAD`; the prompt asks to include ticket IDs like `ABC-123`) and, if `imitateRepoStyle` is on, the titles of the last 20 commits (`git log --no-merges --format=%s`), which `ClaudeClient.styleSection` adds to the prompt as a style reference; the language setting wins over the history's language. If `alwaysIncludeScope` is on, `ClaudeClient.scopeRule` adds a rule that makes the scope mandatory (format line becomes `<type>(<scope>)`) and explicitly overrides the style of recent commits, which otherwise wins (a history without scopes produces titles without scopes). Both calls go through `git()`, which returns null if git fails or isn't installed. If what is sent to Claude changes, update `../PRIVACY.md`.
   - **Fast mode** (`fastMode`, default on): `ClaudeClient.runFast` sends `systemPrompt(...)` (same rules as `prompt`, plus the commit section of the project's CLAUDE.md found by `commitSection` and passed in as `projectCommitRules`) via `--system-prompt` (Unix, env var `CLAUDE_COMMIT_SYSTEM_PROMPT`) or `--system-prompt-file` (Windows, temp file), and `userMessage(...)` (branch, recent titles, rejected suggestion, diff) via stdin, with `--tools "" --strict-mcp-config --no-session-persistence`. Never `--bare` (breaks subscription login) or `--setting-sources`. If the CLI answers with an unknown-option error (`isUnsupportedOption`), it falls back to normal mode for the rest of the IDE session and calls `onFastModeUnsupported` once ("Update Claude Code to use fast mode"). With fast mode off, `runNormal` is the original flow, pinned by `ClaudeClientLegacyGoldenTest`. Measured on this repo: ~85% fewer input tokens (75k → 11k) but no reliable speed difference, because the CLI's default system prompt is served from the prompt cache.
   - `ClaudeClient.suggest` builds an `Invocation` (command, stdin, env) and runs it with `Shell.run`; an `IOException` on start becomes `error.cli.start.failed`. **Windows (experimental)**: `findWindowsClaude` looks for `claude.exe`/`claude.cmd`/`claude.bat` in `PATH`, then `%USERPROFILE%\.local\bin\claude.exe` and `%APPDATA%\npm\claude.cmd`; `windowsInvocation` runs it without a shell (`.cmd`/`.bat` via `cmd.exe /c`) with `claudeArgs` (`-p --tools= --no-session-persistence [--model X]`; `--tools=` instead of `--tools ""` because an empty quoted argument makes `cmd /c` strip the quotes around paths with spaces) and sends prompt + `Diff:` + diff through stdin, since `cmd.exe` mangles multi-line quoted arguments; exit 9009 = command not found. **macOS/Linux**: `unixInvocation` runs `claude -p --tools "" --no-session-persistence [--model X]` through a login shell (`$SHELL -l -c`, falling back to zsh/bash/sh) in an IDE temp directory (`PathManager.getTempPath()/claude-commit`), not in the repo, so it doesn't load the project's CLAUDE.md, hooks or MCP servers. The diff goes through stdin; the prompt and model through environment variables (`CLAUDE_COMMIT_PROMPT`, `CLAUDE_COMMIT_MODEL`) to avoid interpolating text into the script. `--model` is added with `set --` + `"$@"`: `${VAR:+--model "$VAR"}` doesn't work in zsh (it passes a single argument). `~/.local/bin`, `~/.claude/local`, `/opt/homebrew/bin` and `/usr/local/bin` are prepended to `PATH` because IntelliJ launched from the Dock doesn't inherit the terminal's PATH. `ClaudeClient.errorMessage` turns CLI failures into actionable messages: exit 127 → CLI not installed; exit ≠ 0 with a `LOGIN_ERRORS` text (e.g. `Not logged in · Please run /login`, which the CLI writes to stdout) → not logged in or session expired; anything else → raw details. Those texts are only matched when the exit code is ≠ 0, so a commit message that mentions them isn't mistaken for an error.
3. The flow lives in `generate(project, messageControl, panel, previousSuggestion)`; `actionPerformed` calls it with `null`. Success and error notifications offer **Retry**, which calls `generate` again (via `retry`, only while the commit panel is still showing): after a success it passes the rejected message, which `ClaudeClient.retrySection` adds to the prompt to get a different one, and a retry always replaces the message without asking. `ClaudeClient.extraInstructionsSection` adds the user's `extraInstructions` (max 1000 chars) before the reply format. If `overwriteWithoutAsking` is on, the message field is cleared when the button is clicked and restored in `onThrowable`/`onCancel` (only if it's still empty). `onSuccess` asks for confirmation if a message was already typed (unless the setting disables it), writes it via `CommitMessageI.setCommitMessage` and shows a notification from the `"Commit Messages for Claude Code"` group (declared in `plugin.xml`; the id must match `ClaudeCommitSettings.PLUGIN_NAME`).

Settings: `ClaudeCommitSettings` (application service, stored in `claudeCommit.xml`) with model, language (default `IDE` = follow the IDE language via `DynamicBundle.getLocale()`, resolved to an English name such as `Spanish` by `resolveLanguage`; any other value is a fixed language), fast mode, timeout, match repo style, always include a scope, additional instructions and overwrite without asking; UI in `ClaudeCommitConfigurable`. `loadState` migrates the Spanish language names stored by versions ≤ 1.2.0 (`LEGACY_LANGUAGES`).

`Shell.run` is the only place that runs processes: it reads stdout/stderr and writes stdin on `AppExecutorUtil` threads (UTF-8; an `IOException` while writing is ignored and the exit code decides), polls every 200 ms and kills the process if the `ProgressIndicator` is cancelled (throws `ProcessCanceledException`) or the timeout expires.

### Prompt ↔ parser contract

The prompt in `ClaudeClient` requires a reply with `TYPE:`, `REASON:` and `MESSAGE:` lines; `CommitMessageParser.parse` extracts them with regexes (it also accepts the legacy `TIPO`/`RAZON`/`MENSAJE` labels). If the prompt format changes, update the parser and its tests (and vice versa). `CommitMessageParser` has no IntelliJ dependencies on purpose, so it can be tested with plain JUnit. If Claude ignores the format, the whole output is used as the message and the type is inferred from the title prefix; the parser returns the raw type (empty if none) and `GenerateCommitAction.typeLabel` marks types outside `TYPES` as non-standard in the notification.
