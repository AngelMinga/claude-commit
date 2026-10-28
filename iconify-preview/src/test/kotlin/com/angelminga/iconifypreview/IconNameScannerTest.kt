package com.angelminga.iconifypreview

import org.junit.Assert.assertEquals
import org.junit.Test

class IconNameScannerTest {

    private fun ids(text: String) = IconNameScanner.find(text).map { it.id }

    @Test
    fun `finds icons in JSX attributes and expressions`() {
        val text = """
            <Icon icon="emojione-monotone:atm-sign" />
            <Icon icon={'material-symbols:arrow-back-ios-new'} width={20} />
            const icons = [`mdi:home`, "fa6-solid:user"]
        """.trimIndent()
        assertEquals(
            listOf("emojione-monotone:atm-sign", "material-symbols:arrow-back-ios-new", "mdi:home", "fa6-solid:user"),
            ids(text),
        )
    }

    @Test
    fun `offset points at the opening quote`() {
        val text = """icon="mdi:home""""
        val match = IconNameScanner.find(text).single()
        assertEquals(text.indexOf('"'), match.offset)
        assertEquals("mdi", match.prefix)
        assertEquals("home", match.name)
    }

    @Test
    fun `ignores strings that are not icon names`() {
        val text = """
            "https://example.com" 'a':'b' "Mdi:Home" "mdi:" ":home" "mdi:home extra" "mdi--home:x" "a:b:c" "mdi:home'
        """.trimIndent()
        assertEquals(emptyList<String>(), ids(text))
    }

    @Test
    fun `stops after the match limit`() {
        val text = (1..IconNameScanner.MAX_MATCHES + 10).joinToString(" ") { "\"mdi:icon-$it\"" }
        assertEquals(IconNameScanner.MAX_MATCHES, IconNameScanner.find(text).size)
    }
}
