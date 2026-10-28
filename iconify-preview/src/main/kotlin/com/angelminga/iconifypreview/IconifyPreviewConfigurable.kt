package com.angelminga.iconifypreview

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/** Settings → Tools → Iconify Icon Preview. */
internal class IconifyPreviewConfigurable : BoundConfigurable(IconifyPreviewBundle.message("settings.display.name")) {

    private val settings = IconifyPreviewSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox(IconifyPreviewBundle.message("settings.enabled")).bindSelected(settings.state::enabled)
        }
        row(IconifyPreviewBundle.message("settings.api.url")) {
            textField()
                .columns(COLUMNS_LARGE)
                .bindText({ settings.state.apiUrl ?: IconifyPreviewSettings.DEFAULT_API_URL }, { settings.state.apiUrl = it })
                .comment(IconifyPreviewBundle.message("settings.api.url.comment"))
        }
    }

    override fun apply() {
        super.apply()
        // Previews pick up the new settings right away.
        IconifyClient.getInstance().reset()
    }
}
