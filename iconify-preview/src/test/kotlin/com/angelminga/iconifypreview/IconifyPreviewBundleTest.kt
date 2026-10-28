package com.angelminga.iconifypreview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Properties

class IconifyPreviewBundleTest {

    private fun load(name: String) = Properties().apply {
        IconifyPreviewBundleTest::class.java.getResourceAsStream("/messages/$name")!!.reader(Charsets.UTF_8).use(::load)
    }

    @Test
    fun `English and Spanish bundles have the same keys`() {
        assertEquals(
            load("IconifyPreviewBundle.properties").keys.map(Any::toString).sorted(),
            load("IconifyPreviewBundle_es.properties").keys.map(Any::toString).sorted(),
        )
    }
}
