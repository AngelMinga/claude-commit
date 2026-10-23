# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What it is

VS Code port of the JetBrains plugin in `../claude-commit/` (read its `CLAUDE.md` for the full design). A button in the Source Control view title sends the diff to the `claude -p` CLI (subscription, no API key) and streams a Conventional Commits message into the commit input box. Published as `angelminga.commit-messages-for-claude-code` (the name `claude-commit` is taken in the Marketplace). **Code, comments and docs are in English. User-facing text is localized**: `vscode.l10n.t` uses the English text as key, so add every new UI string to `l10n/bundle.l10n.es.json`, and every `%key%` of `package.json` to `package.nls.json` **and** `package.nls.es.json`.

## Structure

```text
src/
├── extension.ts   # activation, commands, git extension API, progress, streaming into the input box, notifications
├── client.ts      # prompts, CLI invocations (Unix/Windows, fast/normal), error messages  ← mirrors ClaudeClient.kt
├── parser.ts      # Claude's reply → CommitSuggestion                                    ← mirrors CommitMessageParser.kt
├── streamJson.ts  # stream-json events (uses JSON.parse)                                  ← mirrors StreamJson.kt
├── diff.ts        # git diff of what would be committed, omitted/truncated files          ← mirrors DiffCollector.kt
├── shell.ts       # process execution with timeout, AbortSignal cancellation and line streaming
├── language.ts    # "IDE" language setting → English language name for the prompt
├── l10n.ts        # t(): English fallback, vscode.l10n.t once activated
└── test/          # node:test unit tests (compiled to out/test)
```

Only `extension.ts` imports `vscode`; everything else is plain Node so it can be tested without VS Code.

## Commands

```bash
npm install
npm test          # tsc + node --test out/test/*.test.js
npm run package   # → commit-messages-for-claude-code-<version>.vsix
```

F5 in VS Code opens an Extension Development Host (`.vscode/launch.json`).

## Keep in sync with the JetBrains plugin

The prompt texts, reply format, CLI flags (`--tools ""`, `--no-session-persistence`, fast mode's `--system-prompt`, `--strict-mcp-config` and stream-json args; never `--bare` or `--setting-sources`), the login shell/PATH handling, Windows CLI lookup and the error messages are ports of the Kotlin code. Change both sides together; `client.test.ts` pins the normal-mode prompt with the same text as `ClaudeClientLegacyGoldenTest`. If what is sent to Claude changes, update `../PRIVACY.md`.

## VS Code specifics

- **What is sent**: staged changes (`git diff --cached`); if nothing is staged, `git diff HEAD` plus untracked files (`git ls-files --others --exclude-standard`, turned into a `/dev/null` diff by `untrackedDiff`), saving the repo's dirty editors first. That matches what VS Code commits with smart commit.
- **Repository and input box**: through the built-in git extension API (`vscode.git`, minimal types declared in `extension.ts`). The `scm/title` command receives the `SourceControl` (its `rootUri`); otherwise the only repo, the active editor's or a quick pick.
- **Running state**: context key `claudeCommit.running` swaps the generate button for a spinner (`claudeCommit.cancel`), which aborts the `AbortController`; `shell.ts` kills the whole process group on macOS/Linux (the CLI runs under a login shell).
- **Streaming**: same rules as the plugin: only written while the box was empty and the user hasn't typed; restored on error/cancel.

## Release

`../.github/workflows/release-vscode.yml`: bump `version` in `package.json`, add a `## <version>` section to `CHANGELOG.md`, push, then `git tag vscode-v<version> && git push origin vscode-v<version>`. It tests, packages, publishes to the VS Code Marketplace (secret `VSCE_PAT`) and Open VSX (secret `OVSX_PAT`, skipped with a warning if missing) and creates a GitHub Release (not marked latest) with the vsix. CI (`ci.yml`, job `vscode`) runs `npm ci`, `npm test` and `vsce package` on every push.
