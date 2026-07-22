package skillbill.review.plan

/** Shared path-condition semantics for stack, lane, assignment, and add-on routing. */
object ReviewPathMatcher {
  fun matches(path: String, signal: String): Boolean {
    val normalizedPath = path.replace('\\', '/').lowercase()
    val normalizedSignal = signal.replace('\\', '/').lowercase()
    if ('*' !in normalizedSignal) return normalizedPath.contains(normalizedSignal)
    val pattern = normalizedSignal.split('*').joinToString(".*") { Regex.escape(it) }
    val regex = Regex("^$pattern$")
    return regex.matches(normalizedPath) || regex.matches(normalizedPath.substringAfterLast('/'))
  }

  fun isIgnored(path: String): Boolean {
    val segments = path.replace('\\', '/').lowercase().split('/')
    val fileName = segments.last()
    return segments.any { it in ignoredPathSegments } || fileName.endsWith(".d.ts") ||
      segments.any { it == "generated" || it.startsWith("generated-") }
  }

  private val ignoredPathSegments = setOf("node_modules", "dist", "build", "coverage", "target", "vendor", "vendored")
}
