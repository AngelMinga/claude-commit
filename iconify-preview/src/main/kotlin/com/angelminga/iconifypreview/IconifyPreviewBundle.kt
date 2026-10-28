package com.angelminga.iconifypreview

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.ResourceBundle

private const val BUNDLE = "messages.IconifyPreviewBundle"

/**
 * UI texts, read from IconifyPreviewBundle_<lang>.properties according to the IDE language.
 *
 * Loaded with plain [ResourceBundle] instead of extending DynamicBundle: its `getMessage` lives in AbstractBundle,
 * which is internal API since 2026.3. `plugin.xml` still uses the bundle through `resource-bundle`/`key` attributes.
 */
object IconifyPreviewBundle {

    // No fallback to the JVM default locale: an English IDE must get the base file even on a Spanish OS.
    private val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        val text = ResourceBundle.getBundle(BUNDLE, DynamicBundle.getLocale(), IconifyPreviewBundle::class.java.classLoader, control)
            .getString(key)
        // Same rule as the platform: MessageFormat only when there are parameters.
        return if (params.isEmpty()) text else MessageFormat.format(text, *params)
    }
}
