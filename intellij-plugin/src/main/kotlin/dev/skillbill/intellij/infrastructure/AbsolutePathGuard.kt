package dev.skillbill.intellij.infrastructure

/**
 * Shared absolute-path detection and redaction for CLI mapping and preference cache.
 */
internal object AbsolutePathGuard {
    private val NAMED_PREFIX_ABSOLUTE_PATH =
        Regex("""(?:[A-Za-z]:\\(?:[^\s"]+)|/(?:home|Users|var|tmp|private|opt)/[^\s"]+)""")

    /** Scheme payload or bare value that embeds a Unix/Windows absolute path. */
    private val SCHEME_OR_BARE_ABSOLUTE_PATH =
        Regex("""(?:^|:)(?:/[^\s"]+|[A-Za-z]:\\[^\s"]+)""")

    fun containsAbsolutePath(value: String): Boolean =
        NAMED_PREFIX_ABSOLUTE_PATH.containsMatchIn(value) ||
            SCHEME_OR_BARE_ABSOLUTE_PATH.containsMatchIn(value)

    fun redact(value: String, replacement: String = "[path]"): String =
        NAMED_PREFIX_ABSOLUTE_PATH.replace(value, replacement)
}
