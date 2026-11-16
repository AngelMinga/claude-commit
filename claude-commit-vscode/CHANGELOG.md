# Changelog

## 0.2.0

- API for other extensions: `activate()` returns `{ version: 1, readSettings, suggest }`, the same message generation as the Source Control button for a diff the caller builds. MultiGit uses it for its "Generate with Claude" button.

## 0.1.1

- The package no longer includes local IDE project files.

## 0.1.0

- First release: a button in the Source Control view generates a Conventional Commits message with the Claude Code CLI, streamed into the commit box.
- Uses the staged changes, or every change (including untracked files) when nothing is staged.
- Fast mode, retry with a different wording, matching the style of recent commits, mandatory scope, additional instructions and the commit section of the project's CLAUDE.md.
- English and Spanish UI.
