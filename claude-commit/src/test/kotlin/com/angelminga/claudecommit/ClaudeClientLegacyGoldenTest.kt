package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the normal-mode (fast mode off) prompt and command line so refactors can't change them. */
class ClaudeClientLegacyGoldenTest {

    @Test
    fun `normal mode prompt is unchanged`() {
        val expected = """
            Analyze the following diff and classify the change according to Conventional Commits.

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

            <optional body with short bullet points, only if it adds value>
        """.trimIndent()
        assertEquals(expected, ClaudeClient.prompt("main", "Spanish", listOf("feat(ui): add button"), true, "Explain why.", "fix: old"))
    }

    @Test
    fun `normal mode unix command is unchanged`() {
        val inv = ClaudeClient.unixInvocation("/bin/zsh", "P", "D", "haiku")
        assertEquals(
            listOf(
                "/bin/zsh", "-l", "-c",
                "export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:/opt/homebrew/bin:/usr/local/bin:\$PATH\"; " +
                    "if [ -n \"\$CLAUDE_COMMIT_MODEL\" ]; then set -- --model \"\$CLAUDE_COMMIT_MODEL\"; else set --; fi; " +
                    "claude -p \"\$CLAUDE_COMMIT_PROMPT\" --tools \"\" --no-session-persistence \"\$@\"",
            ),
            inv.command,
        )
    }
}
