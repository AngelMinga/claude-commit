package com.angelminga.iconifypreview

/** An Iconify icon reference such as `"mdi:home"`; [offset] is the position of its opening quote. */
data class IconMatch(val prefix: String, val name: String, val offset: Int) {
    val id: String get() = "$prefix:$name"
}

/**
 * Finds quoted `prefix:name` strings, the way `@iconify/react` and friends receive icon names. No IntelliJ
 * dependencies, so it works in any file type. Prefixes are not checked here: [IconifyClient] only renders the ones
 * that exist on the Iconify API, which filters out look-alikes such as `"12:30"`.
 */
object IconNameScanner {

    // Iconify naming rules: lowercase letters and digits, words separated by single dashes.
    private const val PART = "[a-z0-9]+(?:-[a-z0-9]+)*"
    private val PATTERN = Regex("""(["'`])($PART):($PART)\1""")

    // Generated files can hold thousands of matches; past this, the editor gets cluttered and slow anyway.
    const val MAX_MATCHES = 2000

    fun find(text: CharSequence): List<IconMatch> =
        PATTERN.findAll(text)
            .take(MAX_MATCHES)
            .map { IconMatch(it.groupValues[2], it.groupValues[3], it.range.first) }
            .toList()
}
