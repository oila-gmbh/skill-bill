package skillbill.review.plan

/** One case-stable matcher for governed changed-line content signals. */
object ReviewContentMatcher {
  fun contains(content: String, signal: String): Boolean = content.contains(signal, ignoreCase = true)

  fun containsAll(content: String, signals: List<String>): Boolean = signals.all { contains(content, it) }
}
