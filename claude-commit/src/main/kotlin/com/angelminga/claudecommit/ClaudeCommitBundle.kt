package com.angelminga.claudecommit

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.ClaudeCommitBundle"

/** UI texts. The platform picks ClaudeCommitBundle_<lang>.properties according to the IDE language. */
object ClaudeCommitBundle : DynamicBundle(ClaudeCommitBundle::class.java, BUNDLE) {

    @Nls
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
