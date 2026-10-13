package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ClaudeCommitSettingsTest {

    private fun loaded(language: String?): String? =
        ClaudeCommitSettings().apply { loadState(ClaudeCommitSettings.Settings().also { it.language = language }) }.state.language

    private fun resolve(stored: String?, ide: String) =
        ClaudeCommitSettings.resolveLanguage(stored, Locale.forLanguageTag(ide))

    @Test
    fun `default language follows the IDE`() {
        assertEquals(ClaudeCommitSettings.LANGUAGE_IDE, ClaudeCommitSettings.Settings().language)
    }

    @Test
    fun `IDE language resolves to the English name of the IDE locale`() {
        assertEquals("Spanish", resolve(ClaudeCommitSettings.LANGUAGE_IDE, "es-BO"))
        assertEquals("English", resolve(ClaudeCommitSettings.LANGUAGE_IDE, "en"))
        assertEquals("Japanese", resolve(null, "ja"))
        assertEquals("Spanish", resolve("  ", "es"))
    }

    @Test
    fun `a fixed language ignores the IDE language`() {
        assertEquals("French", resolve("French", "es"))
        assertEquals("English", resolve("English", "es"))
    }

    @Test
    fun `extra instructions are trimmed and capped`() {
        val settings = ClaudeCommitSettings().apply {
            loadState(ClaudeCommitSettings.Settings().also { it.extraInstructions = "  " + "x".repeat(1500) + "  " })
        }
        assertEquals(ClaudeCommitSettings.MAX_EXTRA_INSTRUCTIONS, settings.extraInstructions.length)
        assertEquals("", ClaudeCommitSettings().extraInstructions)
    }

    @Test
    fun `legacy Spanish language names are migrated`() {
        assertEquals("Spanish", loaded("español"))
        assertEquals("English", loaded("inglés"))
        assertEquals("German", loaded("Alemán"))
    }

    @Test
    fun `current and custom values are kept`() {
        assertEquals("French", loaded("French"))
        assertEquals("Klingon", loaded("Klingon"))
        assertEquals(ClaudeCommitSettings.LANGUAGE_IDE, loaded(ClaudeCommitSettings.LANGUAGE_IDE))
    }
}
