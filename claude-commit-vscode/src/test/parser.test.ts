import * as assert from 'node:assert/strict';
import { test } from 'node:test';
import { parse, partialMessage } from '../parser';

test('expected format', () => {
    const s = parse('TYPE: feat\nREASON: Adds a new feature.\nMESSAGE:\nfeat(login): add Google sign-in\n\n- New button on the login screen');
    assert.equal(s.type, 'feat');
    assert.equal(s.reason, 'Adds a new feature.');
    assert.equal(s.message, 'feat(login): add Google sign-in\n\n- New button on the login screen');
});

test('legacy Spanish labels are still accepted', () => {
    const s = parse('TIPO: fix\nRAZÓN: Corrige un bug.\nMENSAJE:\nfix: evitar NPE al guardar');
    assert.equal(s.type, 'fix');
    assert.equal(s.reason, 'Corrige un bug.');
    assert.equal(s.message, 'fix: evitar NPE al guardar');
});

test('strips code fences', () => {
    assert.equal(parse('```text\nTYPE: fix\nREASON: Fixes a bug.\nMESSAGE:\nfix: avoid NPE on save\n```').message, 'fix: avoid NPE on save');
    assert.equal(parse('```\nTYPE: docs\nREASON: README only.\nMESSAGE:\ndocs: update README\n```').message, 'docs: update README');
    assert.equal(parse('TYPE: chore\nREASON: Maintenance.\nMESSAGE:\n```\nchore: clean up logs\n```').message, 'chore: clean up logs');
});

test('type and labels are case insensitive', () => {
    const s = parse('type: Refactor\nReason: Reorganizes code.\nmessage: refactor: extract service');
    assert.equal(s.type, 'refactor');
    assert.equal(s.reason, 'Reorganizes code.');
    assert.equal(s.message, 'refactor: extract service');
});

test('without format infers the type from the title', () => {
    assert.deepEqual(parse('perf(db)!: cache frequent queries'), { type: 'perf', reason: '', message: 'perf(db)!: cache frequent queries' });
    assert.equal(parse('TYPE: feature\nREASON: x\nMESSAGE:\nfeature: something').type, 'feature');
    assert.deepEqual(parse('Updated several things'), { type: '', reason: '', message: 'Updated several things' });
});

test('partial message shows only what follows MESSAGE', () => {
    assert.equal(partialMessage('TYPE: feat\nREASON: adds'), null);
    assert.equal(partialMessage('TYPE: feat\nREASON: adds\nMESSAGE:\n'), null);
    assert.equal(partialMessage('TYPE: feat\nREASON: x\nMESSAGE:\nfeat(ui): add'), 'feat(ui): add');
    assert.equal(partialMessage('```text\nTYPE: feat\nREASON: x\nMESSAGE:\nfeat: a\n\n- b\n``'), 'feat: a\n\n- b');
});
