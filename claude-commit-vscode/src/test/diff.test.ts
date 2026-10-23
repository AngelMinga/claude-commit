import * as assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { collect, omittedReason, parseNameStatus } from '../diff';
import { languageName, resolveLanguage } from '../language';
import { CancelledError, run } from '../shell';

test('generated, lock and minified files are omitted', () => {
    assert.equal(omittedReason('claude-commit/bin/main/com/x/ClaudeClient.kt'), 'generated or build output');
    assert.equal(omittedReason('web/dist/app.js'), 'generated or build output');
    assert.equal(omittedReason('node_modules/x/index.js'), 'generated or build output');
    assert.equal(omittedReason('frontend/package-lock.json'), 'lock file');
    assert.equal(omittedReason('go.sum'), 'lock file');
    assert.equal(omittedReason('static/app.min.js'), 'minified file or source map');
    assert.equal(omittedReason('static/app.js.map'), 'minified file or source map');
});

test('source files are kept, including packages named like build folders', () => {
    assert.equal(omittedReason('src/main/java/com/acme/build/Builder.java'), null);
    assert.equal(omittedReason('build.gradle.kts'), null);
    assert.equal(omittedReason('docs/output.md'), null);
    assert.equal(omittedReason('C:\\repo\\src\\out\\Main.kt'), null);
});

test('parses name-status output with renames', () => {
    assert.deepEqual(parseNameStatus('M\0a.ts\0R087\0old name.ts\0new name.ts\0A\0b.ts\0D\0c.ts\0'), [
        { status: 'MODIFIED', path: 'a.ts' },
        { status: 'MOVED/RENAMED', path: 'new name.ts', oldPath: 'old name.ts' },
        { status: 'NEW', path: 'b.ts' },
        { status: 'DELETED', path: 'c.ts' },
    ]);
});

test('language names for the prompt', () => {
    assert.equal(languageName('es'), 'Spanish');
    assert.equal(languageName('pt-br'), 'Portuguese');
    assert.equal(resolveLanguage('IDE', 'ja'), 'Japanese');
    assert.equal(resolveLanguage(undefined, 'en'), 'English');
    assert.equal(resolveLanguage('German', 'es'), 'German');
});

function repo(): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'claude-commit-test-'));
    const git = (...args: string[]) => execFileSync('git', args, { cwd: dir, stdio: 'pipe' });
    git('init', '-q');
    git('config', 'user.email', 'test@example.com');
    git('config', 'user.name', 'Test');
    git('config', 'commit.gpgsign', 'false');
    fs.writeFileSync(path.join(dir, 'a.txt'), 'one\n');
    fs.writeFileSync(path.join(dir, 'package-lock.json'), '{}\n');
    git('add', '.');
    git('commit', '-q', '-m', 'init');
    return dir;
}

test('without staged changes uses the working tree and untracked files', async () => {
    const dir = repo();
    fs.writeFileSync(path.join(dir, 'a.txt'), 'one\ntwo\n');
    fs.writeFileSync(path.join(dir, 'package-lock.json'), '{"x":1}\n');
    fs.writeFileSync(path.join(dir, 'new file.txt'), 'hello\n');
    fs.writeFileSync(path.join(dir, 'image.bin'), Buffer.from([1, 0, 2]));
    const res = await collect(dir);
    assert.equal(res.staged, false);
    assert.equal(res.fileCount, 4);
    assert.match(res.diff, /### MODIFIED: a\.txt\n[\s\S]*\+two/);
    assert.match(res.diff, /### MODIFIED: package-lock\.json\n\[lock file: content omitted\]/);
    assert.match(res.diff, /### NEW: new file\.txt\n--- \/dev\/null\n\+\+\+ b\/new file\.txt\n@@ -0,0 \+1,1 @@\n\+hello/);
    assert.match(res.diff, /### NEW: image\.bin\n\[binary file\]/);
});

test('with staged changes uses only those', async () => {
    const dir = repo();
    fs.writeFileSync(path.join(dir, 'a.txt'), 'staged\n');
    fs.renameSync(path.join(dir, 'package-lock.json'), path.join(dir, 'other.txt'));
    execFileSync('git', ['add', '-A'], { cwd: dir });
    fs.writeFileSync(path.join(dir, 'untracked.txt'), 'x\n');
    let savedWorkingTree = false;
    const res = await collect(dir, undefined, async () => (savedWorkingTree = true));
    assert.equal(res.staged, true);
    assert.equal(savedWorkingTree, false);
    assert.match(res.diff, /\+staged/);
    assert.match(res.diff, /### MOVED\/RENAMED: other\.txt\n\(previously: package-lock\.json\)/);
    assert.ok(!res.diff.includes('untracked.txt'));
});

test('shell streams lines, writes large stdin and can be cancelled', async () => {
    const lines: string[] = [];
    const big = 'x'.repeat(1_000_000);
    const res = await run([process.execPath, '-e', 'let n=0;process.stdin.on("data",c=>n+=c.length).on("end",()=>console.log("a\\nb "+n))'], {
        cwd: os.tmpdir(), stdin: big, timeoutSec: 10, onStdoutLine: (l) => lines.push(l),
    });
    assert.equal(res.exitCode, 0);
    assert.deepEqual(lines, ['a', 'b 1000000']);

    const controller = new AbortController();
    const slow = run([process.execPath, '-e', 'setTimeout(()=>{},10000)'], { cwd: os.tmpdir(), timeoutSec: 10, signal: controller.signal });
    setTimeout(() => controller.abort(), 100);
    await assert.rejects(slow, CancelledError);

    await assert.rejects(run([process.execPath, '-e', 'setTimeout(()=>{},10000)'], { cwd: os.tmpdir(), timeoutSec: 1 }), /Timed out after 1s/);
});
