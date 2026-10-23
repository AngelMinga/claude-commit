import * as fs from 'node:fs';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { ClientSettings, commitSection, parseRecentCommits, RECENT_COMMITS, suggest } from './client';
import { collect, git } from './diff';
import { setTranslator, t } from './l10n';
import { resolveLanguage } from './language';
import { CommitSuggestion, partialMessage, TYPES } from './parser';
import { CancelledError } from './shell';

/**
 * Button in the Source Control view title:
 * 1. Takes what git would commit (staged changes, or every change when nothing is staged).
 * 2. Passes the diff to `claude -p` (uses your Claude subscription).
 * 3. Streams the message into the commit input box and shows the detected type; the notification offers "Retry".
 */

const MAX_CLAUDE_MD_BYTES = 512 * 1024;
const MAX_EXTRA_INSTRUCTIONS = 1000;

// Minimal subset of the built-in git extension API (extensions/git/src/api/git.d.ts).
interface GitExtension {
    getAPI(version: 1): GitAPI;
}
interface GitAPI {
    readonly repositories: Repository[];
    getRepository(uri: vscode.Uri): Repository | null;
}
interface Repository {
    readonly rootUri: vscode.Uri;
    readonly inputBox: { value: string };
}

/** The request in progress; null when idle. Only one runs at a time. */
let running: AbortController | null = null;

export function activate(context: vscode.ExtensionContext): void {
    setTranslator((message, ...args) => vscode.l10n.t(message, ...args));
    context.subscriptions.push(
        vscode.commands.registerCommand('claudeCommit.generate', (arg?: { rootUri?: vscode.Uri }) => start(arg)),
        vscode.commands.registerCommand('claudeCommit.cancel', () => running?.abort()),
    );
}

export function deactivate(): void {
    running?.abort();
}

async function start(arg?: { rootUri?: vscode.Uri }): Promise<void> {
    const api = await gitApi();
    if (!api) return;
    const repo = await pickRepository(api, arg?.rootUri);
    if (repo) await generate(api, repo, null);
}

async function gitApi(): Promise<GitAPI | null> {
    const ext = vscode.extensions.getExtension<GitExtension>('vscode.git');
    try {
        if (ext) return (await ext.activate()).getAPI(1);
    } catch {
        // Git disabled ("git.enabled": false) or not installed.
    }
    void vscode.window.showWarningMessage(t('The built-in Git extension is not available. Enable it to generate commit messages.'));
    return null;
}

/** The repository of the clicked Source Control view, the only one, the active editor's, or one the user picks. */
async function pickRepository(api: GitAPI, rootUri?: vscode.Uri): Promise<Repository | undefined> {
    const fromArg = rootUri ? api.getRepository(rootUri) : null;
    if (fromArg) return fromArg;
    if (api.repositories.length === 0) {
        void vscode.window.showWarningMessage(t('No Git repository is open.'));
        return undefined;
    }
    if (api.repositories.length === 1) return api.repositories[0];
    const activeUri = vscode.window.activeTextEditor?.document.uri;
    const fromEditor = activeUri ? api.getRepository(activeUri) : null;
    if (fromEditor) return fromEditor;
    const picked = await vscode.window.showQuickPick(
        api.repositories.map((r) => ({ label: path.basename(r.rootUri.fsPath), description: r.rootUri.fsPath, repo: r })),
        { placeHolder: t('Choose the repository') },
    );
    return picked?.repo;
}

function readSettings(): ClientSettings & { imitateRepoStyle: boolean; overwriteWithoutAsking: boolean } {
    const c = vscode.workspace.getConfiguration('claudeCommit');
    const timeout = c.get<number>('timeoutSec', 180);
    return {
        // The custom model, if set, overrides the one picked from the list.
        model: c.get<string>('customModel', '').trim() || c.get<string>('model', '').trim(),
        effort: c.get<string>('effort', '').trim(),
        claudePath: c.get<string>('claudePath', '').trim(),
        language: resolveLanguage(c.get<string>('language'), vscode.env.language),
        extraInstructions: c.get<string>('extraInstructions', '').trim().slice(0, MAX_EXTRA_INSTRUCTIONS),
        alwaysIncludeScope: c.get<boolean>('alwaysIncludeScope', false),
        fastMode: c.get<boolean>('fastMode', true),
        timeoutSec: Math.min(Math.max(Number.isFinite(timeout) ? timeout : 180, 10), 900),
        imitateRepoStyle: c.get<boolean>('imitateRepoStyle', true),
        overwriteWithoutAsking: c.get<boolean>('overwriteWithoutAsking', false),
    };
}

/**
 * Runs the whole flow. Also used by "Retry", which passes the [previousSuggestion] (null after an error) so Claude
 * writes a different message.
 */
async function generate(api: GitAPI, repo: Repository, previousSuggestion: string | null): Promise<void> {
    if (running) return;
    const settings = readSettings();
    const root = repo.rootUri.fsPath;
    const input = repo.inputBox;
    const controller = new AbortController();
    setRunning(controller);

    // When overwriting without asking (or retrying, which asks for a replacement), clear the box right away so it's
    // clear the extension is working. Keep the text to restore it if Claude fails or the request is cancelled.
    const replaceWithoutAsking = previousSuggestion !== null || settings.overwriteWithoutAsking;
    const previousMessage = input.value;
    const clearedUpfront = replaceWithoutAsking && previousMessage.trim() !== '';
    if (clearedUpfront) input.value = '';

    // Streaming: the message is written into the box as it arrives, but only if the box is empty now (it never
    // overwrites the user's text) and only while the user doesn't type in it.
    let streaming = input.value.trim() === '';
    let lastStreamed: string | null = null;
    const onPartialText = (raw: string) => {
        const text = partialMessage(raw);
        if (text === null || !streaming) return;
        const current = input.value;
        if (current.trim() === '' || current === lastStreamed) {
            input.value = text;
            lastStreamed = text;
        } else {
            streaming = false; // the user typed something: leave the box alone
        }
    };

    /** Puts back the text cleared at the start and removes a partially streamed message, unless the user typed. */
    const restorePrevious = () => {
        streaming = false;
        const current = input.value;
        const untouched = current.trim() === '' || current === lastStreamed;
        if (untouched && (clearedUpfront || lastStreamed !== null)) input.value = previousMessage;
    };

    let result: CommitSuggestion | null = null;
    let error: unknown = null;
    try {
        result = await withProgress(async (report) => {
            report(t('Preparing diff…'));
            const collected = await collect(root, controller.signal, () => saveDirtyEditors(root));
            if (collected.fileCount === 0) throw new NoChangesError();
            if (collected.diff.trim() === '') throw new Error(t('Could not build the diff of the changes.'));

            const branch = (await git(root, ['rev-parse', '--abbrev-ref', 'HEAD'], controller.signal))?.trim() ?? '';
            const recentCommits = settings.imitateRepoStyle
                ? parseRecentCommits(
                      (await git(root, ['log', '-n', String(RECENT_COMMITS), '--no-merges', '--format=%s'], controller.signal)) ?? '',
                  )
                : [];
            const projectCommitRules = settings.fastMode ? findProjectCommitRules(root) : null;

            report(t('Asking Claude…'));
            return suggest(settings, {
                diff: collected.diff,
                branch,
                recentCommits,
                previousSuggestion,
                projectCommitRules,
                signal: controller.signal,
                onFastModeUnsupported: () => void vscode.window.showWarningMessage(t('Update Claude Code to use fast mode.')),
                onPartialText,
            });
        });
    } catch (e) {
        error = e;
    } finally {
        streaming = false;
        setRunning(null);
    }

    if (error !== null || result === null) {
        restorePrevious();
        if (error instanceof CancelledError) return;
        if (error instanceof NoChangesError) {
            void vscode.window.showWarningMessage(t('There are no changes to commit.'));
            return;
        }
        const message = error instanceof Error ? error.message : String(error);
        // After an error, retry the same request (no previous suggestion to avoid).
        notify('error', message, () => retry(api, repo, previousSuggestion));
        return;
    }

    // Text the extension streamed into the box is its own, so it's replaced without asking.
    const current = input.value;
    const ownText = current === lastStreamed;
    if (!replaceWithoutAsking && !ownText && !(await confirmOverwrite(current))) return;
    input.value = result.message;
    const suggestion = result;
    const detail = t('Detected type: {0}', typeLabel(suggestion.type)) + (suggestion.reason ? ` — ${suggestion.reason}` : '');
    notify('info', detail, () => retry(api, repo, suggestion.message));
}

class NoChangesError extends Error {}

/** Progress in the Source Control view and in the status bar, with the elapsed seconds. */
function withProgress<T>(task: (report: (step: string) => void) => Promise<T>): Thenable<T> {
    return vscode.window.withProgress({ location: vscode.ProgressLocation.SourceControl }, () =>
        vscode.window.withProgress(
            { location: vscode.ProgressLocation.Window, title: t('Claude is analyzing your changes…') },
            async (progress) => {
                const startedAt = Date.now();
                let step = '';
                const show = () => {
                    const seconds = Math.floor((Date.now() - startedAt) / 1000);
                    progress.report({ message: `${step} ${t('{0} s elapsed', seconds)}` });
                };
                const ticker = setInterval(show, 1000);
                try {
                    return await task((s) => {
                        step = s;
                        show();
                    });
                } finally {
                    clearInterval(ticker);
                }
            },
        ),
    );
}

function setRunning(controller: AbortController | null): void {
    running = controller;
    void vscode.commands.executeCommand('setContext', 'claudeCommit.running', controller !== null);
}

/** "Retry" from a notification: only possible while the repository is still open. */
function retry(api: GitAPI, repo: Repository, previousSuggestion: string | null): void {
    if (!api.repositories.includes(repo)) {
        void vscode.window.showWarningMessage(t('The repository is no longer open.'));
        return;
    }
    void generate(api, repo, previousSuggestion);
}

/** The diff of unstaged changes is read from disk, so save the repository's dirty editors first. */
async function saveDirtyEditors(root: string): Promise<void> {
    const inRepo = (doc: vscode.TextDocument) =>
        doc.isDirty && doc.uri.scheme === 'file' && !path.relative(root, doc.uri.fsPath).startsWith('..');
    await Promise.all(vscode.workspace.textDocuments.filter(inRepo).map((doc) => doc.save()));
}

/**
 * Commit section of the project's CLAUDE.md (fast mode only: the CLI runs outside the project and doesn't load it).
 * Looks in the repository root and the workspace folder that contains it, at `CLAUDE.md` and `.claude/CLAUDE.md`.
 */
function findProjectCommitRules(root: string): string | null {
    const folder = vscode.workspace.getWorkspaceFolder(vscode.Uri.file(root))?.uri.fsPath;
    const dirs = [...new Set([root, folder].filter((d): d is string => !!d))];
    for (const file of dirs.flatMap((d) => [path.join(d, 'CLAUDE.md'), path.join(d, '.claude', 'CLAUDE.md')])) {
        try {
            const stat = fs.statSync(file);
            if (!stat.isFile() || stat.size >= MAX_CLAUDE_MD_BYTES) continue;
            const section = commitSection(fs.readFileSync(file, 'utf8'));
            if (section) return section;
        } catch {
            // Missing or unreadable: try the next one.
        }
    }
    return null;
}

async function confirmOverwrite(current: string): Promise<boolean> {
    if (current.trim() === '') return true;
    const replace = t('Replace');
    const choice = await vscode.window.showWarningMessage(
        t('There is already a commit message. Replace it with the generated one?'),
        { modal: true },
        replace,
        t('Keep'),
    );
    return choice === replace;
}

function typeLabel(type: string): string {
    if (type.trim() === '') return t('unknown');
    return TYPES.includes(type) ? type : t('{0} (non-standard)', type);
}

function notify(kind: 'info' | 'error', message: string, onRetry: () => void): void {
    const retryLabel = t('Retry');
    const show = kind === 'info' ? vscode.window.showInformationMessage : vscode.window.showErrorMessage;
    void show(message, retryLabel).then((choice) => {
        if (choice === retryLabel) onRetry();
    });
}
