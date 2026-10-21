package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientPromptTest {

    private val styleHeader = "Titles of the latest commits in the repository"

    @Test
    fun `without recent commits there is no style section`() {
        val prompt = ClaudeClient.prompt("main", "English")
        assertFalse(prompt.contains(styleHeader))
        assertTrue(prompt.startsWith("Analyze the following diff"))
    }

    @Test
    fun `recent commits go before the reply format`() {
        val prompt = ClaudeClient.prompt("feature/ABC-1", "Spanish", listOf("feat(ui): add button", "fix: null check"))
        val style = prompt.indexOf(styleHeader)
        val format = prompt.indexOf("Reply EXACTLY")
        assertTrue(style in 0 until format)
        assertTrue(prompt.contains("\n- feat(ui): add button\n- fix: null check"))
        assertTrue(prompt.contains("always write in Spanish"))
    }

    @Test
    fun `prompt lines are not indented`() {
        val prompt = ClaudeClient.prompt("main", "English", listOf("chore: something"))
        for (line in listOf("Analyze the following diff", "Valid types:", "Reply EXACTLY", "TYPE: <type>", "MESSAGE:")) {
            assertTrue("'$line' should start at the beginning of a line", prompt.lines().any { it.startsWith(line) })
        }
    }

    @Test
    fun `scope is optional by default`() {
        val prompt = ClaudeClient.prompt("main", "English", listOf("feat: something"))
        assertTrue(prompt.contains("<type>(<optional scope>):"))
        assertFalse(prompt.contains("Always include a scope"))
    }

    @Test
    fun `always include scope makes it mandatory and overrides the style`() {
        val prompt = ClaudeClient.prompt("main", "English", listOf("feat: something"), alwaysIncludeScope = true)
        assertTrue(prompt.contains("<type>(<scope>):"))
        assertFalse(prompt.contains("<optional scope>"))
        val rule = prompt.indexOf("Always include a scope")
        assertTrue(rule > prompt.indexOf(styleHeader))
        assertTrue(rule < prompt.indexOf("Reply EXACTLY"))
        assertTrue(prompt.contains("takes precedence over the style of recent commits"))
    }

    @Test
    fun `no extra instructions or retry section by default`() {
        val prompt = ClaudeClient.prompt("main", "English")
        assertFalse(prompt.contains("Additional instructions from the user"))
        assertFalse(prompt.contains("rejected this previous suggestion"))
    }

    @Test
    fun `extra instructions go before the reply format`() {
        val prompt = ClaudeClient.prompt("main", "English", extraInstructions = "  Explain why in the body.  ")
        val extra = prompt.indexOf("Additional instructions from the user")
        assertTrue(extra in 0 until prompt.indexOf("Reply EXACTLY"))
        assertTrue(prompt.contains("\nExplain why in the body.\n"))
    }

    @Test
    fun `retry includes the rejected suggestion and asks for a different one`() {
        val prompt = ClaudeClient.prompt("main", "English", previousSuggestion = "feat(ui): add button\n\n- Detail")
        val retry = prompt.indexOf("rejected this previous suggestion")
        assertTrue(retry in 0 until prompt.indexOf("Reply EXACTLY"))
        assertTrue(prompt.contains("different wording"))
        assertTrue(prompt.contains("\nfeat(ui): add button\n\n- Detail\n"))
    }

    @Test
    fun `git log parsing drops blanks, truncates and limits`() {
        val output = "  feat: one  \n\n" + "x".repeat(200) + "\n" + (1..30).joinToString("\n") { "fix: $it" }
        val commits = ClaudeClient.parseRecentCommits(output)
        assertEquals(ClaudeClient.RECENT_COMMITS, commits.size)
        assertEquals("feat: one", commits[0])
        assertEquals(121, commits[1].length)
        assertTrue(commits[1].endsWith("…"))
    }

    @Test
    fun `empty git log yields no commits`() {
        assertTrue(ClaudeClient.parseRecentCommits("\n  \n").isEmpty())
    }
}
