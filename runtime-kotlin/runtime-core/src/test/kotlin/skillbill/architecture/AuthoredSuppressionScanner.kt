package skillbill.architecture

object AuthoredSuppressionScanner {
  fun scan(relativePath: String, lines: Sequence<String>): List<ArchitectureScanSupport.AuthoredSuppression> {
    val state = ScanState(relativePath)
    lines.forEach { rawLine -> state.handleLine(rawLine.trim()) }
    state.flushPending()
    return state.sites
  }

  private class ScanState(private val relativePath: String) {
    val sites = mutableListOf<ArchitectureScanSupport.AuthoredSuppression>()
    var topLevelSymbol = "<file>"
    var currentSymbol = "<file>"
    private val pendingRules = mutableListOf<String>()

    fun handleLine(trimmed: String) {
      if (!isAuthoredSuppressionLine(trimmed)) {
        handleNonSuppressionLine(trimmed)
        return
      }
      handleSuppressionLine(trimmed)
    }

    fun flushPending() {
      pendingRules.forEach { rule ->
        sites += ArchitectureScanSupport.AuthoredSuppression(relativePath, currentSymbol, rule)
      }
      pendingRules.clear()
    }

    private fun flushPendingAndResetSymbol() {
      flushPending()
      currentSymbol = topLevelSymbol
    }

    private fun handleNonSuppressionLine(trimmed: String) {
      val matches = declarationMatches(trimmed)
      applyDeclarationContext(trimmed, matches)
      maybeFlushPendingOnAnchor(trimmed, matches)
      if (trimmed == "}" && pendingRules.isNotEmpty()) {
        flushPendingAndResetSymbol()
      }
    }

    private fun handleSuppressionLine(trimmed: String) {
      if (trimmed.startsWith("@file:Suppress") || trimmed.startsWith("@file:SuppressWarnings")) {
        pendingRules += suppressionRules(trimmed)
        currentSymbol = "<file>"
        return
      }
      val inlineSuppress = INLINE_SUPPRESS_PATTERN.find(trimmed)
      if (inlineSuppress != null) {
        pendingRules += inlineSuppress.groupValues[1].split(',').map { token -> token.trim().removeSurrounding("\"") }
      }
      val standaloneSuppress = trimmed.startsWith("@Suppress") || trimmed.startsWith("@file:Suppress")
      if (standaloneSuppress && inlineSuppress == null) {
        pendingRules += suppressionRules(trimmed)
        return
      }
      val matches = declarationMatches(trimmed)
      applyDeclarationContext(trimmed, matches)
      maybeFlushPendingOnAnchor(trimmed, matches)
    }

    private fun applyDeclarationContext(trimmed: String, matches: DeclarationMatches) {
      val declarationMatch = matches.topLevel
      if (declarationMatch != null && trimmed.startsWith(declarationMatch.value.trim())) {
        topLevelSymbol = declarationMatch.groupValues[2]
        currentSymbol = topLevelSymbol
      }
      val functionMatch = matches.function
      if (functionMatch != null) {
        currentSymbol = functionMatch.groupValues[1]
      }
    }

    private fun maybeFlushPendingOnAnchor(trimmed: String, matches: DeclarationMatches) {
      if (pendingRules.isEmpty()) return
      val anchored = matches.function != null || matches.topLevel != null || trimmed.contains('=')
      if (!anchored) return
      flushPendingAndResetSymbol()
    }
  }

  private data class DeclarationMatches(
    val topLevel: MatchResult?,
    val function: MatchResult?,
  )

  private fun declarationMatches(trimmed: String): DeclarationMatches {
    val code = trimmed.withoutCommentText()
    return DeclarationMatches(
      topLevel = TOP_LEVEL_DECLARATION_PATTERN.find(code),
      function = FUNCTION_DECLARATION_PATTERN.find(code),
    )
  }

  private fun isAuthoredSuppressionLine(trimmed: String): Boolean {
    if (!trimmed.contains("@Suppress")) return false
    val excluded =
      trimmed.contains("writeText(") ||
        trimmed.contains("listOf(\"@Suppress") ||
        trimmed.contains("Never ") ||
        trimmed.contains("do not add @Suppress")
    if (excluded) return false
    return trimmed.startsWith("@Suppress") ||
      trimmed.startsWith("@file:Suppress") ||
      INLINE_SUPPRESS_PATTERN.containsMatchIn(trimmed)
  }

  private fun suppressionRules(annotationLine: String): List<String> = SUPPRESS_RULE_PATTERN.findAll(annotationLine)
    .map { match -> match.groupValues[1] }
    .toList()

  private fun String.withoutCommentText(): String {
    var remaining = this
    val output = StringBuilder()
    while (remaining.isNotEmpty()) {
      val next = nextCommentBoundary(remaining)
      if (next == null) {
        output.append(remaining)
        remaining = ""
      } else {
        output.append(remaining.take(next.start))
        remaining = if (next.isLineComment) {
          ""
        } else {
          remaining.drop(next.endExclusive)
        }
      }
    }
    return output.toString()
  }

  private data class CommentBoundary(val start: Int, val endExclusive: Int, val isLineComment: Boolean)

  private fun nextCommentBoundary(remaining: String): CommentBoundary? {
    val lineComment = remaining.indexOf("//").takeUnless { index -> index == -1 } ?: remaining.length
    val blockComment = remaining.indexOf("/*").takeUnless { index -> index == -1 } ?: remaining.length
    if (lineComment == remaining.length && blockComment == remaining.length) return null
    if (lineComment <= blockComment) {
      return CommentBoundary(lineComment, remaining.length, isLineComment = true)
    }
    val end = remaining.indexOf("*/", blockComment + 2)
    if (end == -1) {
      return CommentBoundary(blockComment, remaining.length, isLineComment = false)
    }
    return CommentBoundary(blockComment, end + 2, isLineComment = false)
  }

  private val INLINE_SUPPRESS_PATTERN =
    Regex("""@Suppress(?:Warnings)?\(\s*"([^"]+)"\s*\)""")
  private val SUPPRESS_RULE_PATTERN = Regex(""""([^"]+)"""")
  private val TOP_LEVEL_DECLARATION_PATTERN =
    Regex(
      """^\s*((?:(?:public|internal|private|protected|abstract|sealed|open|final|data|enum|value|fun)\s+)*)""" +
        """(?:class|object|interface|fun)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
    )
  private val FUNCTION_DECLARATION_PATTERN =
    Regex("""\bfun\s+(?:<[^>]+>\s+)?(?:[A-Za-z_?.][A-Za-z0-9_?]*\.)*([A-Za-z_][A-Za-z0-9_]*)\s*[\(<]""")
}
