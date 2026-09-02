package skillbill.architecture

import kotlin.io.path.readText

private val INJECT_ANNOTATION_PATTERN = Regex("""@Inject\b""")
private const val CLASS_MODIFIERS =
  "public|internal|private|protected|open|abstract|sealed|value|data|annotation|inner|final"
private val INJECT_TYPE_PATTERN =
  Regex("""@Inject\s+(?:\n\s*)*(?:(?:$CLASS_MODIFIERS)\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)""")
private const val NON_PRIVATE_PROPERTY_MODIFIERS =
  "public|internal|protected|open|override|final|lateinit|const|abstract"
private val NON_PRIVATE_PROPERTY_DEFAULT_PATTERN =
  Regex(
    """^\s*(?:@\w+\s+)*(?:(?:$NON_PRIVATE_PROPERTY_MODIFIERS)\s+)*(?:val|var)\s+""" +
      """([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=""",
  )
private val CLASS_HEADER_TERMINATOR = Regex("""\n\s*\n|\}|\b(?:class|object|interface|fun|typealias)\b""")
private val STRING_LITERAL_PATTERN =
  Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")

private val AMBIENT_CLOCK_FORMS: List<Pair<Regex, String>> = listOf(
  Regex("""\bInstant\.now\s*\(""") to "Instant.now()",
  Regex("""\bLocalDateTime\.now\s*\(""") to "LocalDateTime.now()",
  Regex("""\bLocalDate\.now\s*\(""") to "LocalDate.now()",
  Regex("""\bClock\.systemUTC\s*\(""") to "Clock.systemUTC()",
)

private val AMBIENT_ENVIRONMENT_FORMS: List<Pair<Regex, String>> = listOf(
  Regex("""\bSystem\.getenv\s*\(""") to "System.getenv()",
  Regex("""\bSystem\.getProperty\s*\(""") to "System.getProperty()",
  Regex("""\bPath\.of\s*\(\s*""\s*\)""") to "Path.of(\"\")",
  Regex("""\bPaths\.get\s*\(\s*""\s*\)""") to "Paths.get(\"\")",
)

private fun codeWithoutComments(line: String): String {
  val withoutLineComment = line.substringBefore("//")
  return withoutLineComment.replace(Regex("""/\*.*?\*/"""), "").substringBefore("/*")
}

private fun sourceWithoutCommentsOrLiterals(source: String): String =
  source.lineSequence().joinToString("\n", transform = ::codeWithoutComments)
    .replace(STRING_LITERAL_PATTERN, "")

private fun extractBalanced(source: String, openIndex: Int, open: Char, close: Char): String? {
  if (source.getOrNull(openIndex) != open) return null
  var depth = 0
  var index = openIndex
  while (index < source.length) {
    when (source[index]) {
      open -> depth += 1
      close -> {
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

private typealias AmbientSite = ArchitectureScanSupport.AmbientCallSite
private typealias AuthoredSuppressionSite = ArchitectureScanSupport.AuthoredSuppression

private fun ambientSitesInText(
  relativePath: String,
  text: String,
  forms: List<Pair<Regex, String>>,
): List<AmbientSite> {
  val sites = mutableListOf<AmbientSite>()
  text.lineSequence().forEachIndexed { index, line ->
    val code = codeWithoutComments(line)
    forms.filter { (pattern, _) -> pattern.containsMatchIn(code) }
      .forEach { (_, call) -> sites += AmbientSite(relativePath, index + 1, call) }
  }
  return sites
}

private fun ArchitectureScanSupport.ambientSitesUnder(
  scanRoot: String,
  forms: List<Pair<Regex, String>>,
): List<AmbientSite> = kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
  .flatMap { sourceFile ->
    val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
    ambientSitesInText(relativePath, sourceFile.readText(), forms)
  }
  .sortedWith(compareBy({ it.relativePath }, { it.lineNumber }, { it.call }))

fun ArchitectureScanSupport.encodeAmbientSite(site: AmbientSite): String =
  "${site.relativePath}:${site.lineNumber}:${site.call}"

private fun ArchitectureScanSupport.unlistedAmbientSites(
  sites: List<AmbientSite>,
  baseline: Set<String>,
  guardName: String,
): List<String> = (sites.map { site -> encodeAmbientSite(site) }.toSet() - baseline)
  .sorted()
  .map { site -> "$site is not listed in the $guardName baseline." }

fun ArchitectureScanSupport.ambientClockCallSites(scanRoot: String): List<AmbientSite> =
  ambientSitesUnder(scanRoot, AMBIENT_CLOCK_FORMS)

fun ArchitectureScanSupport.ambientClockViolations(baseline: Set<String>, scanRoot: String): List<String> =
  unlistedAmbientSites(ambientClockCallSites(scanRoot), baseline, "ambient-clock")

fun ArchitectureScanSupport.ambientClockViolationsInSource(
  relativePath: String,
  source: String,
  baseline: Set<String>,
): List<String> = unlistedAmbientSites(
  ambientSitesInText(relativePath, source, AMBIENT_CLOCK_FORMS),
  baseline,
  "ambient-clock",
)

fun ArchitectureScanSupport.ambientEnvironmentCallSites(scanRoot: String): List<AmbientSite> =
  ambientSitesUnder(scanRoot, AMBIENT_ENVIRONMENT_FORMS)

fun ArchitectureScanSupport.ambientEnvironmentViolationsInSource(
  relativePath: String,
  source: String,
  baseline: Set<String>,
): List<String> = unlistedAmbientSites(
  ambientSitesInText(relativePath, source, AMBIENT_ENVIRONMENT_FORMS),
  baseline,
  "ambient-environment",
)

fun ArchitectureScanSupport.parseStringSetBaseline(text: String): Set<String> =
  text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()

fun ArchitectureScanSupport.injectConstructorDefaultSites(
  scanRoot: String = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> = kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
  .flatMap { sourceFile ->
    val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
    injectConstructorDefaultSitesInSource(relativePath, sourceFile.readText())
  }
  .sortedWith(compareBy({ it.relativePath }, { it.symbol }, { it.parameter }))

fun ArchitectureScanSupport.injectConstructorDefaultViolations(
  baseline: Set<String>,
  scanRoot: String = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
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
  val scannable = sourceWithoutCommentsOrLiterals(source)
  val sites = mutableListOf<ArchitectureScanSupport.InjectConstructorDefaultSite>()
  INJECT_TYPE_PATTERN.findAll(scannable).forEach { match ->
    val symbol = match.groupValues[1]
    val headerEnd = afterClassTypeParameters(scannable, match.range.last + 1)
    val defaults = if (scannable.getOrNull(headerEnd) == '(') {
      extractBalanced(scannable, headerEnd, '(', ')')?.let(::defaultArgumentParameters)
    } else {
      classBody(scannable, headerEnd)?.let(::nonPrivatePropertyDefaults)
    }
    defaults.orEmpty().forEach { parameter ->
      sites += ArchitectureScanSupport.InjectConstructorDefaultSite(relativePath, symbol, parameter)
    }
  }
  return sites
}

private fun afterClassTypeParameters(source: String, nameEnd: Int): Int {
  var index = source.skipWhitespace(nameEnd)
  if (source.getOrNull(index) == '<') {
    val typeParameters = extractBalanced(source, index, '<', '>') ?: return index
    index = source.skipWhitespace(index + typeParameters.length)
  }
  return index
}

private fun String.skipWhitespace(from: Int): Int {
  var index = from
  while (index < length && this[index].isWhitespace()) index += 1
  return index
}

private fun classBody(source: String, headerEnd: Int): String? {
  val braceIndex = source.indexOf('{', headerEnd)
  if (braceIndex < 0) return null
  if (CLASS_HEADER_TERMINATOR.containsMatchIn(source.substring(headerEnd, braceIndex))) return null
  return extractBalanced(source, braceIndex, '{', '}')?.removeSurrounding("{", "}")
}

private fun nonPrivatePropertyDefaults(classBody: String): List<String> {
  val names = mutableListOf<String>()
  var depth = 0
  classBody.lineSequence().forEach { code ->
    if (depth == 0) {
      NON_PRIVATE_PROPERTY_DEFAULT_PATTERN.find(code)?.let { match -> names += match.groupValues[1] }
    }
    depth += code.count { it == '{' || it == '(' } - code.count { it == '}' || it == ')' }
    if (depth < 0) depth = 0
  }
  return names
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
