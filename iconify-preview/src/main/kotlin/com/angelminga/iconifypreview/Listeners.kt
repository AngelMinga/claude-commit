package com.angelminga.iconifypreview

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

internal class IconPreviewEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) = EditorIconPreview.attach(event.editor)
    override fun editorReleased(event: EditorFactoryEvent) = EditorIconPreview.detach(event.editor)
}

/** Installing the plugin without a restart: editors that are already open get previews too. */
internal class IconPreviewPluginListener : DynamicPluginListener {
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == PLUGIN_ID) {
            ApplicationManager.getApplication().invokeLater(EditorIconPreview::attachAll)
        }
    }

    private companion object {
        const val PLUGIN_ID = "com.angelminga.iconifypreview"
    }
}
