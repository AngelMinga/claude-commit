# Privacy Policy — Commit Messages for Claude Code

_Last updated: October 12, 2026_

Commit Messages for Claude Code ("the plugin") is a JetBrains IDE plugin that generates commit messages using the Claude Code CLI installed on your computer. This page explains what data the plugin handles and where it goes.

## Summary

- The plugin itself **does not collect, store or send any data** to the plugin author or to any third-party service.
- It has **no telemetry, analytics or tracking**.
- When **you click the button**, the plugin passes the diff of the files you checked to the `claude` CLI on your computer. The CLI sends it to Anthropic to generate the message, under **your own Claude account**.

## What data is sent, and when

Data is only processed when you click **Generar Commit con Claude** in the commit panel. Nothing is sent in the background.

On each click, the plugin builds the following and passes it to the local `claude` CLI:

- the diff of the files you **checked** in the commit panel (unchecked files are not included), limited to 15,000 characters per file and 100,000 characters in total;
- the file paths and their change status (new, modified, deleted, moved);
- the name of the current Git branch;
- the instructions (prompt) that ask Claude to write the message, including the language you selected.

## Who receives it

The plugin does not contact any server itself. It runs the `claude` command-line tool (Claude Code) installed and logged in on your computer. The CLI sends the data above to **Anthropic** to generate the response, using your Claude account.

How Anthropic processes, retains or uses that data is governed by **your agreement with Anthropic** and the [Anthropic Privacy Policy](https://www.anthropic.com/legal/privacy), not by this plugin. The plugin author has no access to it.

To limit what is shared, the plugin runs the CLI with:

- `--tools ""`: Claude cannot read other files or run commands; it only sees the diff above;
- `--no-session-persistence`: the CLI does not save the conversation to your local session history;
- a temporary working directory outside your project, so the CLI does not load your project's `CLAUDE.md`, hooks or MCP servers.

**Do not check files that contain secrets** (passwords, API keys, private data) if you do not want them sent to Anthropic.

## What is stored on your computer

The plugin only stores its settings (model, message language, timeout and the "overwrite without asking" option) in your IDE configuration, in `claudeCommit.xml`. They never leave your computer.

## Changes and contact

Changes to this policy will be published in this file in the [GitHub repository](https://github.com/AngelMinga/claude-commit). For questions, open an issue there.

---

## Resumen en español

- El plugin **no recoge ni envía datos** a su autor ni a terceros, y no tiene telemetría.
- Solo al **pulsar el botón**, pasa a la CLI `claude` de tu equipo el diff de los archivos **marcados**, sus rutas, la rama actual y el prompt. La CLI los envía a **Anthropic** con **tu cuenta de Claude**; su tratamiento lo rige tu acuerdo con Anthropic y su [política de privacidad](https://www.anthropic.com/legal/privacy).
- Claude no puede leer otros archivos ni ejecutar comandos (`--tools ""`) y la sesión no se guarda (`--no-session-persistence`).
- Los ajustes se guardan solo en tu IDE (`claudeCommit.xml`).
- **No marques archivos con secretos** si no quieres que se envíen a Anthropic.
