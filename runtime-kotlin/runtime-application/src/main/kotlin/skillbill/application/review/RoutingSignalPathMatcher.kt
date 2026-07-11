package skillbill.application.review

internal object RoutingSignalPathMatcher {
  fun matches(path: String, signal: String): Boolean {
    val normalizedPath = path.lowercase()
    val normalizedSignal = signal.lowercase()
    if ('*' !in normalizedSignal) return normalizedPath.contains(normalizedSignal)
    val pattern = normalizedSignal
      .split('*')
      .joinToString(".*") { part -> Regex.escape(part) }
    val regex = Regex("^$pattern$")
    return regex.matches(normalizedPath) || regex.matches(normalizedPath.substringAfterLast('/'))
  }

  fun isIgnored(path: String): Boolean {
    val segments = path.lowercase().split('/')
    val fileName = segments.last()
    return segments.any { segment -> segment in ignoredPathSegments } ||
      fileName.endsWith(".d.ts") ||
      segments.any { segment -> segment == "generated" || segment.startsWith("generated-") }
  }

  private val ignoredPathSegments = setOf(
    "node_modules",
    "dist",
    "build",
    "coverage",
    "target",
    "vendor",
    "vendored",
  )
}
