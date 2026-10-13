package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitMessageParserTest {

    @Test
    fun `expected format`() {
        val s = CommitMessageParser.parse(
            """
            TYPE: feat
            REASON: Adds a new feature.
            MESSAGE:
            feat(login): add Google sign-in

            - New button on the login screen
            """.trimIndent()
        )
        assertEquals("feat", s.type)
        assertEquals("Adds a new feature.", s.reason)
        assertEquals("feat(login): add Google sign-in\n\n- New button on the login screen", s.message)
    }

    @Test
    fun `legacy Spanish labels are still accepted`() {
        val s = CommitMessageParser.parse("TIPO: fix\nRAZÓN: Corrige un bug.\nMENSAJE:\nfix: evitar NPE al guardar")
        assertEquals("fix", s.type)
        assertEquals("Corrige un bug.", s.reason)
        assertEquals("fix: evitar NPE al guardar", s.message)
    }

    @Test
    fun `strips code fence with language tag`() {
        val s = CommitMessageParser.parse("```text\nTYPE: fix\nREASON: Fixes a bug.\nMESSAGE:\nfix: avoid NPE on save\n```")
        assertEquals("fix", s.type)
        assertEquals("fix: avoid NPE on save", s.message)
    }

    @Test
    fun `strips code fence without tag`() {
        val s = CommitMessageParser.parse("```\nTYPE: docs\nREASON: README only.\nMESSAGE:\ndocs: update README\n```")
        assertEquals("docs", s.type)
        assertEquals("docs: update README", s.message)
    }

    @Test
    fun `code fence only around the message`() {
        val s = CommitMessageParser.parse("TYPE: chore\nREASON: Maintenance.\nMESSAGE:\n```\nchore: clean up logs\n```")
        assertEquals("chore: clean up logs", s.message)
    }

    @Test
    fun `type and labels are case insensitive`() {
        val s = CommitMessageParser.parse("type: Refactor\nReason: Reorganizes code.\nmessage: refactor: extract service")
        assertEquals("refactor", s.type)
        assertEquals("Reorganizes code.", s.reason)
        assertEquals("refactor: extract service", s.message)
    }

    @Test
    fun `without format infers the type from the title`() {
        val s = CommitMessageParser.parse("perf(db)!: cache frequent queries")
        assertEquals("perf", s.type)
        assertEquals("", s.reason)
        assertEquals("perf(db)!: cache frequent queries", s.message)
    }

    @Test
    fun `non-standard type`() {
        val s = CommitMessageParser.parse("TYPE: feature\nREASON: x\nMESSAGE:\nfeature: something")
        assertEquals("feature", s.type)
    }

    @Test
    fun `free text without type`() {
        val s = CommitMessageParser.parse("Updated several things")
        assertEquals("", s.type)
        assertEquals("Updated several things", s.message)
    }
}
