package com.angelminga.claudecommit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.MessageFormat
import java.util.Properties

class ClaudeCommitBundleTest {

    private fun load(name: String): Properties = Properties().apply {
        val stream = ClaudeCommitBundleTest::class.java.classLoader.getResourceAsStream("messages/$name.properties")
            ?: error("messages/$name.properties not found")
        stream.reader(Charsets.UTF_8).use { load(it) }
    }

    private val english = load("ClaudeCommitBundle")
    private val spanish = load("ClaudeCommitBundle_es")

    private fun placeholders(text: String) = Regex("""\{\d+}""").findAll(text).map { it.value }.toSortedSet()

    @Test
    fun `Spanish has exactly the same keys as English`() {
        assertEquals(english.stringPropertyNames().sorted(), spanish.stringPropertyNames().sorted())
    }

    @Test
    fun `translations keep the same placeholders`() {
        for (key in english.stringPropertyNames()) {
            assertEquals(key, placeholders(english.getProperty(key)), placeholders(spanish.getProperty(key)))
        }
    }

    @Test
    fun `texts with placeholders survive MessageFormat`() {
        for (bundle in listOf(english, spanish)) {
            for (key in bundle.stringPropertyNames()) {
                val text = bundle.getProperty(key)
                val count = placeholders(text).size
                if (count == 0) continue
                val formatted = MessageFormat.format(text, *Array(count) { "X$it" })
                (0 until count).forEach { assertTrue("$key: $formatted", formatted.contains("X$it")) }
            }
        }
    }
}
