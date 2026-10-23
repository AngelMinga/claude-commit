import * as assert from 'node:assert/strict';
import { test } from 'node:test';
import {
    commitSection,
    errorMessage,
    findWindowsClaude,
    fromStream,
    isUnsupportedOption,
    parseRecentCommits,
    prompt,
    systemPrompt,
    unixFastInvocation,
    unixInvocation,
    userMessage,
    windowsFastInvocation,
    windowsInvocation,
} from '../client';
import { parse } from '../parser';
import * as StreamJson from '../streamJson';

// Same expected text as ClaudeClientLegacyGoldenTest in the IntelliJ plugin: both must send the same prompt.
test('normal mode prompt matches the IntelliJ plugin', () => {
    const expected = `Analyze the following diff and classify the change according to Conventional Commits.

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

Current branch: main. If the branch name contains a ticket ID (e.g. ABC-123), include it in the title.

Write the reason, the title and the body in Spanish. The TYPE, REASON and MESSAGE labels are not translated.

Titles of the latest commits in the repository, newest first. Match their style (scopes in use, capitalization, length, ticket ID format), but always write in Spanish and follow the requested Conventional Commits format. They are only a reference: do not copy their content.
- feat(ui): add button

Always include a scope in the title: a short lowercase word naming the area of the code that changed (module, package, component or feature, e.g. settings, client, ui). Reuse a scope from the recent commits when one fits. This rule takes precedence over the style of recent commits.

Additional instructions from the user (follow them unless they conflict with the reply format below):
Explain why.

The user rejected this previous suggestion and wants a different one. Write a new message with different wording; change the type or scope only if a better one fits.
fix: old

Reply EXACTLY in this format, without code blocks:
TYPE: <type>
REASON: <one sentence explaining why it is that type>
MESSAGE:
<type>(<scope>): <title in the imperative mood, max. 72 characters>

<optional body with short bullet points, only if it adds value>`;
    assert.equal(prompt('main', 'Spanish', ['feat(ui): add button'], true, 'Explain why.', 'fix: old'), expected);
});

test('normal mode unix command matches the IntelliJ plugin', () => {
    const inv = unixInvocation('/bin/zsh', 'P', 'D', 'haiku');
    assert.deepEqual(inv.command, [
        '/bin/zsh', '-l', '-c',
        'export PATH="$HOME/.local/bin:$HOME/.claude/local:/opt/homebrew/bin:/usr/local/bin:$PATH"; ' +
            'if [ -n "$CLAUDE_COMMIT_MODEL" ]; then set -- --model "$CLAUDE_COMMIT_MODEL"; else set --; fi; ' +
            'claude -p "$CLAUDE_COMMIT_PROMPT" --tools "" --no-session-persistence "$@"',
    ]);
    assert.equal(inv.stdin, 'D');
    assert.deepEqual(inv.env, { CLAUDE_COMMIT_PROMPT: 'P', CLAUDE_COMMIT_MODEL: 'haiku' });
});

test('unix fast command passes the rules as system prompt and the data through stdin', () => {
    const inv = unixFastInvocation('/bin/zsh', 'RULES', 'DATA', 'sonnet', 'low', '/opt/claude');
    const script = inv.command[3];
    assert.ok(script.endsWith(
        '"$CLAUDE_COMMIT_BIN" -p "$@" --system-prompt "$CLAUDE_COMMIT_SYSTEM_PROMPT" --tools "" --strict-mcp-config --no-session-persistence ' +
            '--output-format stream-json --verbose --include-partial-messages',
    ), script);
    assert.ok(script.includes('set -- "$@" --effort "$CLAUDE_COMMIT_EFFORT"'));
    assert.ok(!script.includes('--bare'));
    assert.equal(inv.stdin, 'DATA');
    assert.deepEqual(inv.env, {
        CLAUDE_COMMIT_SYSTEM_PROMPT: 'RULES', CLAUDE_COMMIT_MODEL: 'sonnet', CLAUDE_COMMIT_EFFORT: 'low', CLAUDE_COMMIT_BIN: '/opt/claude',
    });
});

test('windows commands', () => {
    const fast = windowsFastInvocation('C:\\Users\\Ana Pérez\\npm\\claude.cmd', 'C:\\Temp\\sp.txt', 'DATA', 'haiku');
    assert.deepEqual(fast.command, [
        'cmd.exe', '/c', 'C:\\Users\\Ana Pérez\\npm\\claude.cmd', '-p', '--model', 'haiku',
        '--system-prompt-file', 'C:\\Temp\\sp.txt', '--tools=', '--strict-mcp-config', '--no-session-persistence',
        '--output-format', 'stream-json', '--verbose', '--include-partial-messages',
    ]);
    const normal = windowsInvocation('C:\\claude.exe', 'P', 'D', '');
    assert.deepEqual(normal.command, ['C:\\claude.exe', '-p', '--tools=', '--no-session-persistence']);
    assert.equal(normal.stdin, 'P\n\nDiff:\nD');
});

test('finds the Windows CLI in PATH, then in the default locations', () => {
    const env = { PATH: 'C:\\Windows;"C:\\Tools\\"', USERPROFILE: 'C:\\Users\\ana', APPDATA: 'C:\\Users\\ana\\AppData\\Roaming' };
    assert.equal(findWindowsClaude(env, (f) => f === 'C:\\Tools\\claude.cmd'), 'C:\\Tools\\claude.cmd');
    assert.equal(findWindowsClaude(env, (f) => f === 'C:\\Users\\ana\\.local\\bin\\claude.exe'), 'C:\\Users\\ana\\.local\\bin\\claude.exe');
    assert.equal(findWindowsClaude(env, () => false), null);
});

test('system prompt has the rules and no data', () => {
    const system = systemPrompt('Spanish', {
        hasRecentCommits: true, alwaysIncludeScope: true, extraInstructions: 'Explain why.', isRetry: true,
        projectCommitRules: '## Commits\nUse gitmoji.',
    });
    for (const rule of ['Valid types:', 'in Spanish', 'Match the style of the latest commit titles', 'Always include a scope',
        'Explain why.', 'rejected the previous suggestion', "Commit conventions from the project's CLAUDE.md", 'Use gitmoji.',
        '<type>(<scope>):']) {
        assert.ok(system.includes(rule), `missing: ${rule}`);
    }
    assert.ok(!system.includes('Current branch:'));
    const plain = systemPrompt('English');
    for (const absent of ['Match the style', 'Always include a scope', 'Additional instructions', 'rejected the previous suggestion', 'CLAUDE.md']) {
        assert.ok(!plain.includes(absent), `unexpected: ${absent}`);
    }
});

test('user message carries branch, titles, rejected suggestion and diff in that order', () => {
    assert.equal(
        userMessage('feature/ABC-1', ['feat: one', 'fix: two'], 'fix: old', 'DIFF'),
        'Current branch: feature/ABC-1\n\nTitles of the latest commits, newest first:\n- feat: one\n- fix: two\n\n' +
            'Rejected previous suggestion:\nfix: old\n\nDiff:\nDIFF',
    );
    assert.equal(userMessage('main', [], null, 'D'), 'Current branch: main\n\nDiff:\nD');
});

test('parses git log titles', () => {
    assert.deepEqual(parseRecentCommits('feat: a\n\n  fix: b  \n' + 'x'.repeat(130)), ['feat: a', 'fix: b', 'x'.repeat(120) + '…']);
});

test('extracts only the commit section of CLAUDE.md', () => {
    const md = '# Project\n\n## Build\nRun gradle.\n\n## Commit messages\nUse English.\n\n```text\n# not a heading\n```\n\n' +
        '### Scopes\nUse module names.\n\n## Architecture\nIgnored.';
    assert.equal(commitSection(md), '## Commit messages\nUse English.\n\n```text\n# not a heading\n```\n\n### Scopes\nUse module names.');
    assert.equal(commitSection('# Project\n\n## Build\nRun gradle.'), null);
    assert.equal(commitSection('## Committing\n' + 'x'.repeat(10_000), 100)?.length, 101);
});

test('error messages', () => {
    const error = (exitCode: number, stdout = '', stderr = '') => errorMessage({ exitCode, stdout, stderr });
    assert.equal(error(0, 'TYPE: fix\nREASON: x\nMESSAGE:\nfix: handle "command not found" and "Not logged in"'), null);
    assert.match(error(1, 'Not logged in · Please run /login') ?? '', /^You are not logged in/);
    assert.match(error(1, '', 'API Error: 401 OAuth token has expired.') ?? '', /^You are not logged in/);
    assert.match(error(127, '', 'zsh:1: command not found: claude') ?? '', /^The "claude" CLI was not found/);
    assert.match(error(9009) ?? '', /^The "claude" CLI was not found/);
    assert.match(error(1, '', "error: unknown option '--effort'") ?? '', /option --effort\./);
    assert.equal(error(2, '', 'boom'), 'Claude failed (exit code 2): boom');
    assert.equal(error(0), 'Claude failed (exit code 0): ');
});

// Stream-json lines are trimmed copies of real Claude Code output.
const delta = '{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"YPE: feat\\nRE"}},"session_id":"f72a"}';
const result = '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"result":"TYPE: feat\\nREASON: a\\u00f1ade un \\"saludo\\".\\nMESSAGE:\\nfeat: add greeting","session_id":"x"}';
const notLoggedIn = '{"duration_api_ms":0,"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login","session_id":"5937"}';

test('reads stream-json events', () => {
    assert.equal(StreamJson.textDelta(delta), 'YPE: feat\nRE');
    assert.equal(StreamJson.textDelta('{"type":"stream_event","event":{"type":"message_start"}}'), null);
    assert.equal(StreamJson.textDelta(result), null);
    assert.deepEqual(StreamJson.result(result), { text: 'TYPE: feat\nREASON: añade un "saludo".\nMESSAGE:\nfeat: add greeting', isError: false });
    assert.deepEqual(StreamJson.result(notLoggedIn), { text: 'Not logged in · Please run /login', isError: true });
    assert.equal(StreamJson.result(delta), null);
});

test('stream output becomes a plain result for the parser and the error messages', () => {
    const ok = fromStream({ exitCode: 0, stdout: `${delta}\n${result}\n`, stderr: '' }, 'ignored');
    assert.equal(ok.exitCode, 0);
    assert.equal(parse(ok.stdout).message, 'feat: add greeting');
    // An error result never counts as a success, even with exit code 0.
    const login = fromStream({ exitCode: 0, stdout: `${notLoggedIn}\n`, stderr: '' }, '');
    assert.equal(login.exitCode, 1);
    assert.match(errorMessage(login) ?? '', /^You are not logged in/);
    assert.ok(isUnsupportedOption(fromStream({ exitCode: 1, stdout: '', stderr: "error: unknown option '--include-partial-messages'" }, '')));
    assert.ok(!isUnsupportedOption({ exitCode: 0, stdout: 'fix: handle unknown option in parser', stderr: '' }));
    // No result line (e.g. the process was killed): fall back to the accumulated fragments.
    assert.equal(fromStream({ exitCode: 0, stdout: `${delta}\n`, stderr: '' }, 'TYPE: fix').stdout, 'TYPE: fix');
});
