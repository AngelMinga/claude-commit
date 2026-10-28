package com.angelminga.iconifypreview

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "IconifyPreviewSettings", storages = [Storage("iconifyPreview.xml")])
class IconifyPreviewSettings : SimplePersistentStateComponent<IconifyPreviewSettings.State>(State()) {

    class State : BaseState() {
        var enabled by property(true)
        var apiUrl by string(DEFAULT_API_URL)
    }

    /** API base URL without a trailing slash; blank falls back to the public API. */
    val apiUrl: String
        get() = state.apiUrl?.trim()?.trimEnd('/')?.ifEmpty { null } ?: DEFAULT_API_URL

    companion object {
        const val DEFAULT_API_URL = "https://api.iconify.design"

        fun getInstance(): IconifyPreviewSettings = service()
    }
}
