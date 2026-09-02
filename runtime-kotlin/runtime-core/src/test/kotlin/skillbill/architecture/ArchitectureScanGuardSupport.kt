package skillbill.architecture

import kotlin.io.path.readText

private val INJECT_ANNOTATION_PATTERN = Regex("""@Inject\b""")
private const val CLASS_MODIFIERS =
  "public|internal|private|protected|open|abstract|sealed|value|data|annotation|inner|final"
private val INJECT_TYPE_PATTERN =
  Regex("""@Inject\s+(?:\n\s*)*(?:(?:$CLASS_MODIFIERS)\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val AMBIENT_INSTANT_NOW = Regex("""\bInstant\.now\s*\(""")
private val AMBIENT_LOCAL_DATE_TIME_NOW = Regex("""\bLocalDateTime\.now\s*\(""")
private val AMBIENT_CLOCK_SYSTEM_UTC = Regex("""\bClock\.systemUTC\s*\(""")

private fun codeWithoutComments(line: String): String {
  val withoutLineComment = line.substringBefore("//")
  return withoutLineComment.replace(Regex("""/\*.*?\*/"""), "").substringBefore("/*")
}

private fun extractBalancedParentheses(source: String, openIndex: Int): String? {
  if (source.getOrNull(openIndex) != '(') return null
  var depth = 0
  var index = openIndex
  while (index < source.length) {
    when (source[index]) {
      '(' -> depth += 1
      ')' -> {
        depth -= 1
        if (depth == 0) return source.substring(openIndex, index + 1)
      }
    }
    index += 1
  }
  return null
}

private fun defaultArgumentParameters(constructorBody: String): List<String> {
  val inner = constructorBody.trim().removePrefix("(").removeSuffix(")")
  if (inner.isBlank()) return emptyList()
  val parameters = splitTopLevelParameters(inner)
  return parameters.mapNotNull { parameter ->
    val name = parameter.substringBefore(':').trim().substringAfterLast(' ').ifBlank {
      parameter.substringBefore(':').trim()
    }
    if ('=' in parameter) name.takeIf { it.isNotBlank() } else null
  }
}

private fun splitTopLevelParameters(parameters: String): List<String> {
  val parts = mutableListOf<String>()
  val current = StringBuilder()
  var angleDepth = 0
  var parenDepth = 0
  parameters.forEach { character ->
    when (character) {
      '<' -> angleDepth += 1
      '>' -> angleDepth -= 1
      '(' -> parenDepth += 1
      ')' -> parenDepth -= 1
      ',' -> if (angleDepth == 0 && parenDepth == 0) {
        parts += current.toString().trim()
        current.clear()
        return@forEach
      }
    }
    current.append(character)
  }
  if (current.isNotBlank()) parts += current.toString().trim()
  return parts
}

private typealias AmbientClockSite = ArchitectureScanSupport.AmbientClockCallSite
private typealias AuthoredSuppressionSite = ArchitectureScanSupport.AuthoredSuppression

fun ArchitectureScanSupport.runtimeApplicationAmbientClockCallSites(): List<AmbientClockSite> {
  val root = runtimeRoot.resolve("runtime-kotlin/runtime-application/src/main/kotlin")
  val sites = mutableListOf<AmbientClockSite>()
  kotlinFilesUnder(root).forEach { sourceFile ->
    val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
    sourceFile.readText().lineSequence().forEachIndexed { index, line ->
      val code = codeWithoutComments(line)
      when {
        AMBIENT_INSTANT_NOW.containsMatchIn(code) ->
          sites += AmbientClockSite(relativePath, index + 1, "Instant.now()")
        AMBIENT_LOCAL_DATE_TIME_NOW.containsMatchIn(code) ->
          sites += AmbientClockSite(relativePath, index + 1, "LocalDateTime.now()")
        AMBIENT_CLOCK_SYSTEM_UTC.containsMatchIn(code) ->
          sites += AmbientClockSite(relativePath, index + 1, "Clock.systemUTC()")
      }
    }
  }
  return sites.sortedWith(compareBy({ it.relativePath }, { it.lineNumber }, { it.call }))
}

fun ArchitectureScanSupport.ambientClockViolations(baseline: Set<String>): List<String> {
  val current = runtimeApplicationAmbientClockCallSites()
    .map { site -> "${site.relativePath}:${site.lineNumber}:${site.call}" }
    .toSet()
  return ambientClockViolationsForSites(current, baseline)
}

fun ArchitectureScanSupport.ambientClockViolationsInSource(
  relativePath: String,
  source: String,
  baseline: Set<String>,
): List<String> {
  val sites = mutableListOf<AmbientClockSite>()
  source.lineSequence().forEachIndexed { index, line ->
    val code = codeWithoutComments(line)
    when {
      AMBIENT_INSTANT_NOW.containsMatchIn(code) ->
        sites += AmbientClockSite(relativePath, index + 1, "Instant.now()")
      AMBIENT_LOCAL_DATE_TIME_NOW.containsMatchIn(code) ->
        sites += AmbientClockSite(relativePath, index + 1, "LocalDateTime.now()")
      AMBIENT_CLOCK_SYSTEM_UTC.containsMatchIn(code) ->
        sites += AmbientClockSite(relativePath, index + 1, "Clock.systemUTC()")
    }
  }
  val encoded = sites.map { site -> "${site.relativePath}:${site.lineNumber}:${site.call}" }.toSet()
  return ambientClockViolationsForSites(encoded, baseline)
}

private fun ambientClockViolationsForSites(current: Set<String>, baseline: Set<String>): List<String> =
  (current - baseline).sorted().map { site -> "$site is not listed in the ambient-clock baseline." }

fun ArchitectureScanSupport.parseStringSetBaseline(text: String): Set<String> =
  text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()

fun ArchitectureScanSupport.injectConstructorDefaultSites(
  scanRoot: String = "runtime-kotlin/runtime-application/src/main/kotlin",
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> = kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
  .flatMap { sourceFile ->
    val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
    injectConstructorDefaultSitesInSource(relativePath, sourceFile.readText())
  }
  .sortedWith(compareBy({ it.relativePath }, { it.symbol }, { it.parameter }))

fun ArchitectureScanSupport.injectConstructorDefaultViolations(
  baseline: Set<String>,
  scanRoot: String = "runtime-kotlin/runtime-application/src/main/kotlin",
): List<String> {
  val current = injectConstructorDefaultSites(scanRoot)
    .map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
    .toSet()
  return (current - baseline).sorted().map { site ->
    "$site has a default argument on an @Inject constructor or dependency bag."
  }
}

fun ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
  relativePath: String,
  source: String,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> {
  if (!INJECT_ANNOTATION_PATTERN.containsMatchIn(source)) return emptyList()
  val sites = mutableListOf<ArchitectureScanSupport.InjectConstructorDefaultSite>()
  INJECT_TYPE_PATTERN.findAll(source).forEach { match ->
    val symbol = match.groupValues[1]
    val constructorStart = source.indexOf('(', match.range.last)
    if (constructorStart < 0) return@forEach
    val constructorBody = extractBalancedParentheses(source, constructorStart) ?: return@forEach
    defaultArgumentParameters(constructorBody).forEach { parameter ->
      sites += ArchitectureScanSupport.InjectConstructorDefaultSite(relativePath, symbol, parameter)
    }
  }
  return sites
}

private val COMPLEXITY_SUPPRESSION_RULES: Set<String> = setOf(
  "TooManyFunctions",
  "LargeClass",
  "LongMethod",
  "CyclomaticComplexMethod",
  "ComplexCondition",
  "NestedBlockDepth",
  "ReturnCount",
  "ThrowsCount",
  "LongParameterList",
)

private val SUPPRESSION_SCAN_ROOTS: List<String> = listOf(
  "runtime-kotlin",
  "runtime-kotlin/build-logic",
)

fun ArchitectureScanSupport.parseSuppressionAllowList(decisionsMarkdown: String): Set<Triple<String, String, String>> {
  val sectionStart = decisionsMarkdown.indexOf("Compiler suppression allow-list")
  if (sectionStart < 0) return emptySet()
  val tableBody = decisionsMarkdown.substring(sectionStart)
  val rows = mutableSetOf<Triple<String, String, String>>()
  TABLE_ROW_PATTERN.findAll(tableBody).forEach { match ->
    val path = match.groupValues[1].trim()
    val symbol = match.groupValues[2].trim()
    val rule = match.groupValues[3].trim()
    if (path == "path" || path.startsWith("-")) return@forEach
    if (path.isNotBlank() && symbol.isNotBlank() && rule.isNotBlank()) {
      rows += Triple(path, symbol, rule)
    }
  }
  return rows
}

fun ArchitectureScanSupport.authoredSuppressions(
  scanRoots: List<String> = SUPPRESSION_SCAN_ROOTS,
): List<AuthoredSuppressionSite> = scanRoots.flatMap { scanRoot ->
  kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
    .filter { path -> !path.toString().replace('\\', '/').contains("/generated/") }
    .flatMap { sourceFile ->
      val normalized = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
      val relativePath = normalized.removePrefix("runtime-kotlin/")
      authoredSuppressionsFromFile(relativePath, sourceFile.readText())
    }
}

fun ArchitectureScanSupport.authoredSuppressionsFromFile(
  relativePath: String,
  source: String,
): List<AuthoredSuppressionSite> = AuthoredSuppressionScanner.scan(relativePath, source.lineSequence())

fun ArchitectureScanSupport.authoredSuppressionsInSource(
  relativePath: String,
  source: String,
): List<AuthoredSuppressionSite> = AuthoredSuppressionScanner.scan(relativePath, source.lineSequence())

fun ArchitectureScanSupport.suppressionViolations(
  suppressions: List<AuthoredSuppressionSite>,
  allowList: Set<Triple<String, String, String>>,
): List<String> = suppressions.mapNotNull { site ->
  when {
    site.rule in COMPLEXITY_SUPPRESSION_RULES ->
      "${site.relativePath}::${site.symbol} uses banned complexity suppression '${site.rule}'; refactor instead."
    Triple(site.relativePath, site.symbol, site.rule) !in allowList ->
      "${site.relativePath}::${site.symbol} has @Suppress('${site.rule}') without a dated allow-list row; " +
        "fix the finding or add path, symbol, rule, and why to runtime-kotlin/agent/decisions.md."
    else -> null
  }
}.sorted()

fun ArchitectureScanSupport.detektComplexityPinViolations(detektYaml: String): List<String> =
  COMPLEXITY_SUPPRESSION_RULES.mapNotNull { rule ->
    val section = Regex("""\n\s*$rule:\s*\n\s*active:\s*(true|false)""").find(detektYaml)
    when {
      section == null -> "detekt.yml missing pinned complexity rule '$rule'."
      section.groupValues[1] != "true" -> "detekt.yml must pin '$rule' with active: true."
      else -> null
    }
  }

private val TABLE_ROW_PATTERN =
  Regex(
    """^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|""",
    RegexOption.MULTILINE,
  )
