# Privacy Policy — Commit Messages for Claude Code

_Last updated: October 21, 2026 (version 1.11.0)_

Commit Messages for Claude Code ("the plugin") is a JetBrains IDE plugin that generates commit messages using the Claude Code CLI installed on your computer. This page explains what data the plugin handles and where it goes.

## Summary

- The plugin itself **does not collect, store or send any data** to the plugin author or to any third-party service.
- It has **no telemetry, analytics or tracking**.
- When **you click the button**, the plugin passes the diff of the files you checked to the `claude` CLI on your computer. The CLI sends it to Anthropic to generate the message, under **your own Claude account**.

## What data is sent, and when

Data is only processed when you click **Generate Commit Message with Claude** in the commit panel or **Retry** in its notification. Nothing is sent in the background.

On each click, the plugin builds the following and passes it to the local `claude` CLI:

- the diff of the files you **checked** in the commit panel (unchecked files are not included), limited to 15,000 characters per file and 100,000 characters in total. For generated files (`build/`, `dist/`, `bin/`, `node_modules/`…), lock files and minified files only the path is sent, not the content;
- the file paths and their change status (new, modified, deleted, moved);
- the name of the current Git branch;
- the titles (first line only) of the last 20 commits of the repository, so Claude can match their style. You can turn this off in _Settings → Tools → Commit Messages for Claude Code_ ("Match the style of recent commits in the repository");
- the additional instructions you typed in the settings, if any;
- in fast mode (on by default), the section of the project's `CLAUDE.md` that talks about commits, if there is one (only that section, not the rest of the file);
- when you click **Retry**, the previous suggestion, so Claude can write a different one;
- the instructions (prompt) that ask Claude to write the message, including the language you selected.

## Who receives it

The plugin does not contact any server itself. It runs the `claude` command-line tool (Claude Code) installed and logged in on your computer. The CLI sends the data above to **Anthropic** to generate the response, using your Claude account.

How Anthropic processes, retains or uses that data is governed by **your agreement with Anthropic** and the [Anthropic Privacy Policy](https://www.anthropic.com/legal/privacy), not by this plugin. The plugin author has no access to it.

To limit what is shared, the plugin runs the CLI with:

- `--tools ""`: Claude cannot read other files or run commands; it only sees the data listed above;
- `--no-session-persistence`: the CLI does not save the conversation to your local session history;
- in fast mode, `--strict-mcp-config`: the CLI does not start your MCP servers for this request;
- a temporary working directory outside your project, so the CLI does not load your project's `CLAUDE.md`, hooks or MCP servers (in fast mode the plugin adds only the commit section of `CLAUDE.md`, as listed above).

**Do not check files that contain secrets** (passwords, API keys, private data) if you do not want them sent to Anthropic.

## What is stored on your computer

The plugin only stores its settings (model, message language, timeout, the "fast mode", "match recent commits style", "always include a scope" and "overwrite without asking" options, and your additional instructions) in your IDE configuration, in `claudeCommit.xml`. They are not sent anywhere, except the additional instructions, which are included in the prompt as described above.

## Changes and contact

Changes to this policy will be published in this file in the [GitHub repository](https://github.com/AngelMinga/claude-commit). For questions, open an issue there.
