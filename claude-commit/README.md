# Commit Messages for Claude Code

A JetBrains IDE plugin that writes [Conventional Commits](https://www.conventionalcommits.org) messages for you with the [Claude Code](https://docs.anthropic.com/en/docs/claude-code) CLI.

It adds a button to the commit panel. When you click it, the plugin:

1. Takes the files checked for the commit (including unversioned files and partial commits).
2. Sends the diff to `claude -p`, using your Claude Pro/Max subscription (no API key).
3. Shows a notification with the **detected type** (`feat`, `fix`, `refactor`…) and the reason.
4. Fills in the commit message for you to review.

Not affiliated with Anthropic.

## Requirements

- Claude Code installed and logged in (run `claude` once and log in with your subscription).
- IntelliJ IDEA, WebStorm, Rider, Android Studio, DataGrip or another JetBrains IDE, version 2025.2 or later (verified on 2026.2 and 2026.3).
- To build it: JDK 21 (IntelliJ can download it: *Project Structure → SDK → Download JDK*).

## Install

From the IDE (once published on JetBrains Marketplace): *Settings → Plugins → Marketplace*, search for **Commit Messages for Claude Code**.

From a file: *Settings → Plugins → ⚙ → Install Plugin from Disk…* → choose the `.zip` → restart the IDE.

## Usage

1. Open the commit panel (`⌘K`).
2. Check the files to commit.
3. Click **Generate Commit Message with Claude** next to *Amend*.
4. Review the message and commit.

Optional shortcut: *Settings → Keymap* → search for "Generate Commit Message with Claude".

## Settings

*Settings → Tools → Commit Messages for Claude Code*:

- **Model**: CLI default, Haiku (fastest), Sonnet or Opus.
- **Message language**: English by default.
- **Timeout** in seconds.
- **Match the style of recent commits**: sends the titles of the last 20 commits so Claude follows your scopes and conventions.
- **Replace the existing message without asking**.

The plugin UI is available in English and Spanish and follows the IDE language (Spanish requires a Spanish language pack from JetBrains Marketplace). The message language above is set independently.

See [PRIVACY.md](../PRIVACY.md) for what is sent to Claude.

## Build

**Option A – from IntelliJ (easiest)**

1. *File → Open* → select the `claude-commit` folder.
2. Wait for the Gradle import (use JDK 21 if asked).
3. **Gradle** tool window → `Tasks → intellij platform → buildPlugin`.

**Option B – terminal**

```bash
brew install gradle      # first time only
cd claude-commit
gradle wrapper           # generates ./gradlew
./gradlew buildPlugin
```

The plugin is written to `build/distributions/claude-commit-<version>.zip`.

To try it without installing: `./gradlew runIde` opens a sandbox IntelliJ with the plugin loaded.

## Customize

The prompt (types, format, style section) lives in
`src/main/kotlin/com/angelminga/claudecommit/ClaudeClient.kt`.

## License

[MIT](../LICENSE)
