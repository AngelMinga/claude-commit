package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeCommitSettingsTest {

    private fun loaded(language: String?): String? =
        ClaudeCommitSettings().apply { loadState(ClaudeCommitSettings.Settings().also { it.language = language }) }.state.language

    @Test
    fun `default language is English`() {
        assertEquals("English", ClaudeCommitSettings.Settings().language)
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
    }
}
