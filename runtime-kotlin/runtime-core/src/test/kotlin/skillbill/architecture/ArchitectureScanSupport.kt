package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ArchitectureScanSupport {
  val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { start ->
      var dir: Path? = start
      while (dir != null) {
        if (Files.isDirectory(dir.resolve("runtime-kotlin"))) return@let dir
        dir = dir.parent
      }
      start
    }

  data class ParseBoundarySite(val relativePath: String, val functionNames: Set<String>)

  data class LineCeilingExemption(val relativePath: String, val reason: String)

  fun kotlinFilesUnder(root: Path): List<Path> {
    if (!Files.exists(root)) return emptyList()
    return Files.walk(root).use { paths ->
      paths
        .filter { path ->
          path.isRegularFile() &&
            path.extension == "kt" &&
            !path.toString().replace('\\', '/').contains("/build/")
        }
        .toList()
    }
  }

  fun declaredPackage(source: String): String? = PACKAGE_PATTERN.find(source)?.groupValues?.get(1)

  fun primaryTopLevelDeclarationName(source: String): String? {
    var braceDepth = 0
    source.lineSequence().forEach { rawLine ->
      val line = rawLine.withoutCommentText().text
      if (braceDepth == 0) {
        TOP_LEVEL_DECLARATION_PATTERN.find(line)?.groupValues?.get(2)?.let { return it }
      }
      braceDepth += line.count { character -> character == '{' }
      braceDepth -= line.count { character -> character == '}' }
      if (braceDepth < 0) braceDepth = 0
    }
    return null
  }

  fun packageClusteringViolations(sourceRoots: List<String>, genericSegments: Set<String>): List<String> {
    val filesByPackage = linkedMapOf<String, MutableList<Pair<Path, String>>>()
    sourceRoots.forEach { sourceRoot ->
      kotlinFilesUnder(runtimeRoot.resolve(sourceRoot)).forEach { sourceFile ->
        val source = sourceFile.readText()
        val packageName = declaredPackage(source) ?: return@forEach
        val primaryName = primaryTopLevelDeclarationName(source) ?: sourceFile.fileName.toString().removeSuffix(".kt")
        filesByPackage.getOrPut(packageName) { mutableListOf() }.add(sourceFile to primaryName)
      }
    }
    val allPackages = filesByPackage.keys
    val violations = mutableListOf<String>()
    filesByPackage.forEach { (packageName, looseFiles) ->
      val childPackages = allPackages.filter { child ->
        child.startsWith("$packageName.") && "." !in child.removePrefix("$packageName.")
      }
      if (childPackages.isEmpty()) return@forEach
      val areaChildren = childPackages
        .map { child -> child.substringAfterLast('.') }
        .filterNot { segment -> segment in genericSegments }
        .toSet()
      if (areaChildren.isEmpty()) return@forEach
      val parentNoun = packageName.substringAfterLast('.')
      looseFiles.forEach { (sourceFile, primaryName) ->
        val matchedChild = matchingAreaChild(primaryName, areaChildren, parentNoun)
        if (matchedChild != null) {
          violations += "${runtimeRoot.relativize(sourceFile)} in $packageName belongs to cluster '$matchedChild'; " +
            "move it under $packageName.$matchedChild or a deeper cluster package."
        }
      }
    }
    return violations.sorted()
  }

  fun productionLineCeilingViolations(
    productionRoots: List<String>,
    ceiling: Int,
    exemptions: Map<String, String>,
  ): List<String> {
    val violations = mutableListOf<String>()
    productionRoots.forEach { productionRoot ->
      kotlinFilesUnder(runtimeRoot.resolve(productionRoot)).forEach { sourceFile ->
        val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
        if (isNonProductionKotlinSourceSet(relativePath)) return@forEach
        violations += productionLineCeilingViolationsInSource(
          relativePath = relativePath,
          source = sourceFile.readText(),
          ceiling = ceiling,
          exemptions = exemptions,
        )
      }
    }
    return violations.sorted()
  }

  fun productionLineCeilingViolationsInSource(
    relativePath: String,
    source: String,
    ceiling: Int,
    exemptions: Map<String, String>,
  ): List<String> {
    val lineCount = source.lineSequence().count()
    if (lineCount <= ceiling || relativePath in exemptions) return emptyList()
    return listOf(
      "$relativePath has $lineCount lines; split it below the $ceiling-line ceiling " +
        "or add an explicit exemption with reason.",
    )
  }

  fun isNonProductionKotlinSourceSet(relativePath: String): Boolean {
    val normalized = relativePath.replace('\\', '/')
    return "/src/test/" in normalized ||
      "/src/testFixtures/" in normalized ||
      "/src/repoTest/" in normalized ||
      "/src/androidTest/" in normalized ||
      "/src/androidUnitTest/" in normalized ||
      "/src/commonTest/" in normalized ||
      "/src/jvmTest/" in normalized ||
      "/src/nativeTest/" in normalized
  }

  fun inlineFqnViolations(scanRoots: List<String>, prefixes: List<String>): List<String> {
    val violations = mutableListOf<String>()
    scanRoots.forEach { scanRoot ->
      kotlinFilesUnder(runtimeRoot.resolve(scanRoot)).forEach { sourceFile ->
        val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
        if (relativePath.contains("/build/generated/")) return@forEach
        inlineFqnReferences(sourceFile.readText(), prefixes).forEach { reference ->
          violations += "$relativePath contains inline reference $reference; import the type or add a documented alias."
        }
      }
    }
    return violations.sorted()
  }

  fun parseBoundaryViolations(sites: List<ParseBoundarySite>): List<String> {
    val violations = mutableListOf<String>()
    sites.forEach { site ->
      val sourceFile = runtimeRoot.resolve(site.relativePath)
      val source = sourceFile.readText()
      extractFunctionBodies(source, site.functionNames).forEach { (functionName, body) ->
        forbiddenParseBoundaryReporter(body).forEach { reporter ->
          violations += "${site.relativePath}::$functionName reports malformed external input via $reporter; " +
            "use a typed contract failure instead."
        }
      }
    }
    return violations.sorted()
  }

  fun conventionReapplicationViolations(
    moduleBuildFiles: List<Path>,
    ownedPatterns: List<Pair<String, String>>,
  ): List<String> {
    val violations = mutableListOf<String>()
    moduleBuildFiles.forEach { buildFile ->
      val relativePath = runtimeRoot.relativize(buildFile).toString().replace('\\', '/')
      violations += conventionReapplicationViolationsInText(
        relativePath = relativePath,
        text = buildFile.readText(),
        ownedPatterns = ownedPatterns,
      )
    }
    return violations.sorted()
  }

  fun conventionReapplicationViolationsInText(
    relativePath: String,
    text: String,
    ownedPatterns: List<Pair<String, String>>,
  ): List<String> {
    val violations = mutableListOf<String>()
    ownedPatterns.forEach { (pattern, settingName) ->
      if (Regex(pattern).containsMatchIn(text)) {
        violations +=
          "$relativePath re-applies $settingName already owned by configureKotlinJvm " +
          "via skillbill.jvm-library."
      }
    }
    return violations.sorted()
  }

  fun inlineFqnReferences(source: String, prefixes: List<String>): List<String> = sourceWithoutStringLiterals(source)
    .lineSequence()
    .filterNot { line ->
      val trimmed = line.trim()
      trimmed.startsWith("import ") ||
        trimmed.startsWith("package ") ||
        trimmed.startsWith("//") ||
        trimmed.startsWith("*") ||
        trimmed.startsWith("/*")
    }
    .flatMap { line ->
      val codeLine = line.substringBefore("//")
      prefixes.flatMap { prefix -> inlineFqnMatches(codeLine, prefix) }
    }
    .distinct()
    .sorted()
    .toList()

  private fun sourceWithoutStringLiterals(source: String): String {
    val output = StringBuilder()
    var index = 0
    while (index < source.length) {
      when {
        source.startsWith("\"\"\"", index) -> {
          val end = source.indexOf("\"\"\"", index + 3)
          if (end == -1) {
            output.append("\"\"\"")
            break
          }
          output.append("\"\"\"")
          index = end + 3
        }
        source[index] == '"' -> {
          output.append('"')
          index++
          while (index < source.length) {
            when (source[index]) {
              '\\' -> index += 2
              '"' -> {
                output.append('"')
                index++
                break
              }
              else -> index++
            }
          }
        }
        else -> {
          output.append(source[index])
          index++
        }
      }
    }
    return output.toString()
  }

  private fun inlineFqnMatches(line: String, prefix: String): List<String> {
    val escaped = Regex.escape(prefix)
    val pattern = when (prefix) {
      "java.",
      "javax.",
      "jakarta.",
      "kotlin.",
      "kotlinx.",
      -> Regex("""\b$escaped(?:[a-z][a-z0-9]*\.)+[A-Z][A-Za-z0-9_]*(?:\.[a-z][A-Za-z0-9_]*)*\b""")
      else -> Regex("""\b$escaped(?:[a-z][a-z0-9]*\.)+[A-Z][A-Za-z0-9_]*(?:\.[a-zA-Z][A-Za-z0-9_]*)*\b""")
    }
    return pattern.findAll(line)
      .map { match -> match.value }
      .filter { reference -> reference.count { character -> character == '.' } >= 2 }
      .toList()
  }

  private fun forbiddenParseBoundaryReporter(body: String): List<String> {
    val reporters = mutableListOf<String>()
    if (Regex("""\berror\s*\(""").containsMatchIn(body)) reporters += "error()"
    if (Regex("""\brequire\s*\(""").containsMatchIn(body)) reporters += "require()"
    if (Regex("""\bthrow\s+IllegalArgumentException\b""").containsMatchIn(body)) {
      reporters += "throw IllegalArgumentException"
    }
    if (Regex("""\bthrow\s+RuntimeException\b""").containsMatchIn(body)) reporters += "throw RuntimeException"
    return reporters
  }

  private fun extractFunctionBodies(source: String, functionNames: Set<String>): Map<String, String> {
    val bodies = linkedMapOf<String, String>()
    var braceDepth = 0
    var captureStartDepth = -1
    var capturingName: String? = null
    val capture = StringBuilder()
    source.lineSequence().forEach { rawLine ->
      val line = rawLine.withoutCommentText().text
      if (capturingName == null) {
        val match = FUNCTION_PATTERN.find(line)
        if (match != null && match.groupValues[1] in functionNames) {
          capturingName = match.groupValues[1]
          captureStartDepth = braceDepth
          capture.clear()
        }
      }
      if (capturingName != null) {
        capture.appendLine(rawLine)
        braceDepth += line.count { character -> character == '{' }
        braceDepth -= line.count { character -> character == '}' }
        if (braceDepth <= captureStartDepth && line.contains('}')) {
          bodies[capturingName] = capture.toString()
          capturingName = null
          captureStartDepth = -1
          capture.clear()
        }
      } else {
        braceDepth += line.count { character -> character == '{' }
        braceDepth -= line.count { character -> character == '}' }
        if (braceDepth < 0) braceDepth = 0
      }
    }
    return bodies
  }

  fun parseBoundaryViolationsInSource(source: String, site: ParseBoundarySite): List<String> {
    val violations = mutableListOf<String>()
    extractFunctionBodies(source, site.functionNames).forEach { (functionName, body) ->
      forbiddenParseBoundaryReporter(body).forEach { reporter ->
        violations += "${site.relativePath}::$functionName reports malformed external input via $reporter; " +
          "use a typed contract failure instead."
      }
    }
    return violations.sorted()
  }

  fun packageClusteringViolationMessage(
    packageName: String,
    primaryName: String,
    areaChildren: Set<String>,
    genericSegments: Set<String>,
  ): String? {
    val parentNoun = packageName.substringAfterLast('.')
    val filteredChildren = areaChildren.filterNot { segment -> segment in genericSegments }.toSet()
    if (filteredChildren.isEmpty()) return null
    val matchedChild = matchingAreaChild(primaryName, filteredChildren, parentNoun) ?: return null
    return "$packageName/$primaryName.kt belongs to cluster '$matchedChild'; move it under $packageName.$matchedChild."
  }

  private fun matchingAreaChild(primaryName: String, areaChildren: Set<String>, parentNoun: String): String? {
    val normalized = camelTokens(primaryName).joinToString("")
    return areaChildren.firstOrNull { area ->
      area != parentNoun &&
        (
          normalized.contains(area) ||
            area in primaryName.lowercase() ||
            primaryName.lowercase().contains(area)
          )
    }
  }

  private fun camelTokens(name: String): List<String> =
    CAMEL_TOKEN_PATTERN.findAll(name).map { match -> match.value.lowercase() }.toList()

  private data class SourceLine(val text: String)

  private fun String.withoutCommentText(): SourceLine {
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
    return SourceLine(output.toString())
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

  private val PACKAGE_PATTERN = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
  private val IMPORT_PATTERN = Regex("""^\s*import\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
  private val TOP_LEVEL_DECLARATION_PATTERN =
    Regex(
      """^\s*((?:(?:public|internal|private|protected|abstract|sealed|open|final|data|enum|value|fun)\s+)*)""" +
        """(?:class|object|interface|fun)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
    )
  private val FUNCTION_PATTERN = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
  private val CAMEL_TOKEN_PATTERN = Regex("""[A-Z]?[a-z]+|[A-Z]+(?=[A-Z][a-z]|\b)""")
  private val EXTENSION_FUN_PATTERN =
    Regex("""^\s*(?:(?:public|internal|private|protected)\s+)*fun\s+([A-Za-z0-9_.]+)\.""")
  private val INJECT_ANNOTATION_PATTERN = Regex("""@Inject\b""")
  private val INJECT_TYPE_PATTERN =
    Regex("""@Inject\s+(?:\n\s*)*(?:data\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)""")
  private val AMBIENT_INSTANT_NOW = Regex("""\bInstant\.now\s*\(""")
  private val AMBIENT_LOCAL_DATE_TIME_NOW = Regex("""\bLocalDateTime\.now\s*\(""")
  private val AMBIENT_CLOCK_SYSTEM_UTC = Regex("""\bClock\.systemUTC\s*\(""")

  data class ApplicationPackageCycle(val areas: List<String>)

  data class AmbientClockCallSite(val relativePath: String, val lineNumber: Int, val call: String)

  data class InjectConstructorDefaultSite(val relativePath: String, val symbol: String, val parameter: String)

  fun declaredImports(source: String): List<String> =
    IMPORT_PATTERN.findAll(source).map { match -> match.groupValues[1] }.toList()

  fun logicalTypeLineCounts(productionRoots: List<String>): Map<String, Int> {
    val counts = linkedMapOf<String, Int>()
    productionRoots.forEach { productionRoot ->
      kotlinFilesUnder(runtimeRoot.resolve(productionRoot)).forEach { sourceFile ->
        val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
        if (isNonProductionKotlinSourceSet(relativePath)) return@forEach
        val source = sourceFile.readText()
        val lineCount = source.lineSequence().count()
        val packageName = declaredPackage(source) ?: return@forEach
        val topLevelType = primaryTopLevelDeclarationName(source)
        val targets =
          if (topLevelType != null) {
            listOf("$packageName.$topLevelType")
          } else {
            extensionReceiverFqns(source, packageName, declaredImports(source))
          }
        if (targets.isEmpty()) return@forEach
        targets.distinct().forEach { fqn ->
          counts[fqn] = counts.getOrDefault(fqn, 0) + lineCount
        }
      }
    }
    return counts
  }

  fun logicalTypeLineCeilingViolations(
    productionRoots: List<String>,
    ceiling: Int,
    baseline: Map<String, Int>,
  ): List<String> {
    val counts = logicalTypeLineCounts(productionRoots)
    val violations = mutableListOf<String>()
    counts.forEach { (fqn, lineCount) ->
      val baselineCount = baseline[fqn]
      when {
        baselineCount != null && lineCount > baselineCount ->
          violations += "$fqn has $lineCount lines; baseline allows $baselineCount."
        baselineCount == null && lineCount > ceiling ->
          violations +=
            "$fqn has $lineCount lines; exceeds the $ceiling-line ceiling without a baseline entry."
      }
    }
    return violations.sorted()
  }

  fun parseIntBaseline(text: String): Map<String, Int> =
    text.lineSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .mapNotNull { line ->
        val parts = line.split(Regex("""\s+"""), limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val count = parts[1].toIntOrNull() ?: return@mapNotNull null
        parts[0] to count
      }
      .toMap()

  fun applicationPackageImportEdges(): Map<String, Set<String>> {
    val edges = linkedMapOf<String, MutableSet<String>>()
    val root = runtimeRoot.resolve("runtime-kotlin/runtime-application/src/main/kotlin")
    kotlinFilesUnder(root).forEach { sourceFile ->
      val source = sourceFile.readText()
      val packageName = declaredPackage(source) ?: return@forEach
      if (!packageName.startsWith("skillbill.application.")) return@forEach
      val area = packageName.removePrefix("skillbill.application.").substringBefore('.')
      if (area.isBlank()) return@forEach
      declaredImports(source)
        .filter { it.startsWith("skillbill.application.") }
        .map { imported -> imported.removePrefix("skillbill.application.").substringBefore('.') }
        .filter { it.isNotBlank() && it != area }
        .forEach { importedArea -> edges.getOrPut(area) { mutableSetOf() }.add(importedArea) }
    }
    return edges.mapValues { (_, value) -> value.toSet() }
  }

  fun applicationPackageCycles(): Set<ApplicationPackageCycle> =
    mutualImportCyclesForEdges(applicationPackageImportEdges())
      .map { cycle -> ApplicationPackageCycle(cycle) }
      .toSet()

  fun applicationPackageCycleViolations(baselineCycles: Set<ApplicationPackageCycle>): List<String> =
    packageCycleViolationsForEdges(applicationPackageImportEdges(), baselineCycles)

  fun packageCycleViolationsForEdges(
    edges: Map<String, Set<String>>,
    baselineCycles: Set<ApplicationPackageCycle>,
  ): List<String> {
    val baselineKeys = baselineCycles.map { cycle -> cycle.areas.sorted().joinToString("|") }.toSet()
    val currentKeys = mutualImportCyclesForEdges(edges)
      .map { cycle -> cycle.sorted().joinToString("|") }
      .toSet()
    return (currentKeys - baselineKeys).sorted().map { cycle ->
      "New package cycle not in baseline: ${cycle.replace("|", " <-> ")}"
    }
  }

  private fun mutualImportCyclesForEdges(edges: Map<String, Set<String>>): List<List<String>> {
    val cycles = linkedSetOf<List<String>>()
    edges.forEach { (from, targets) ->
      targets.forEach { to ->
        if (from != to && edges[to]?.contains(from) == true) {
          cycles += listOf(from, to).sorted()
        }
      }
    }
    return cycles.toList()
  }

  fun parsePackageCycleBaseline(text: String): Set<ApplicationPackageCycle> =
    text.lineSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .map { line ->
        ApplicationPackageCycle(line.split('|').map(String::trim).filter(String::isNotBlank).sorted())
      }
      .toSet()

  fun runtimeApplicationAmbientClockCallSites(): List<AmbientClockCallSite> {
    val root = runtimeRoot.resolve("runtime-kotlin/runtime-application/src/main/kotlin")
    val sites = mutableListOf<AmbientClockCallSite>()
    kotlinFilesUnder(root).forEach { sourceFile ->
      val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
      sourceFile.readText().lineSequence().forEachIndexed { index, line ->
        val code = line.withoutCommentText().text
        when {
          AMBIENT_INSTANT_NOW.containsMatchIn(code) ->
            sites += AmbientClockCallSite(relativePath, index + 1, "Instant.now()")
          AMBIENT_LOCAL_DATE_TIME_NOW.containsMatchIn(code) ->
            sites += AmbientClockCallSite(relativePath, index + 1, "LocalDateTime.now()")
          AMBIENT_CLOCK_SYSTEM_UTC.containsMatchIn(code) ->
            sites += AmbientClockCallSite(relativePath, index + 1, "Clock.systemUTC()")
        }
      }
    }
    return sites.sortedWith(compareBy({ it.relativePath }, { it.lineNumber }, { it.call }))
  }

  fun ambientClockViolations(baseline: Set<String>): List<String> {
    val current = runtimeApplicationAmbientClockCallSites()
      .map { site -> "${site.relativePath}:${site.lineNumber}:${site.call}" }
      .toSet()
    return ambientClockViolationsForSites(current, baseline)
  }

  fun ambientClockViolationsInSource(
    relativePath: String,
    source: String,
    baseline: Set<String>,
  ): List<String> {
    val sites = mutableListOf<AmbientClockCallSite>()
    source.lineSequence().forEachIndexed { index, line ->
      val code = line.withoutCommentText().text
      when {
        AMBIENT_INSTANT_NOW.containsMatchIn(code) ->
          sites += AmbientClockCallSite(relativePath, index + 1, "Instant.now()")
        AMBIENT_LOCAL_DATE_TIME_NOW.containsMatchIn(code) ->
          sites += AmbientClockCallSite(relativePath, index + 1, "LocalDateTime.now()")
        AMBIENT_CLOCK_SYSTEM_UTC.containsMatchIn(code) ->
          sites += AmbientClockCallSite(relativePath, index + 1, "Clock.systemUTC()")
      }
    }
    val encoded = sites.map { site -> "${site.relativePath}:${site.lineNumber}:${site.call}" }.toSet()
    return ambientClockViolationsForSites(encoded, baseline)
  }

  private fun ambientClockViolationsForSites(current: Set<String>, baseline: Set<String>): List<String> =
    (current - baseline).sorted().map { site -> "$site is not listed in the ambient-clock baseline." }

  fun parseStringSetBaseline(text: String): Set<String> =
    text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()

  fun injectConstructorDefaultSites(
    scanRoot: String = "runtime-kotlin/runtime-application/src/main/kotlin",
  ): List<InjectConstructorDefaultSite> =
    kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
      .flatMap { sourceFile ->
        val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
        injectConstructorDefaultSitesInSource(relativePath, sourceFile.readText())
      }
      .sortedWith(compareBy({ it.relativePath }, { it.symbol }, { it.parameter }))

  fun injectConstructorDefaultViolations(
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

  fun injectConstructorDefaultSitesInSource(
    relativePath: String,
    source: String,
  ): List<InjectConstructorDefaultSite> {
    if (!INJECT_ANNOTATION_PATTERN.containsMatchIn(source)) return emptyList()
    val sites = mutableListOf<InjectConstructorDefaultSite>()
    INJECT_TYPE_PATTERN.findAll(source).forEach { match ->
      val symbol = match.groupValues[1]
      val constructorStart = source.indexOf('(', match.range.last)
      if (constructorStart < 0) return@forEach
      val constructorBody = extractBalancedParentheses(source, constructorStart) ?: return@forEach
      defaultArgumentParameters(constructorBody).forEach { parameter ->
        sites += InjectConstructorDefaultSite(relativePath, symbol, parameter)
      }
    }
    return sites
  }

  fun extensionReceiverFqns(source: String, packageName: String, imports: List<String>): List<String> {
    val importMap = imports.associateBy { imported -> imported.substringAfterLast('.') }
    val receivers = linkedSetOf<String>()
    var braceDepth = 0
    source.lineSequence().forEach { rawLine ->
      val line = rawLine.withoutCommentText().text
      if (braceDepth == 0) {
        EXTENSION_FUN_PATTERN.find(line)?.groupValues?.get(1)?.let { receiverType ->
          resolveTypeFqn(receiverType, packageName, importMap)?.let(receivers::add)
        }
      }
      braceDepth += line.count { character -> character == '{' }
      braceDepth -= line.count { character -> character == '}' }
      if (braceDepth < 0) braceDepth = 0
    }
    return receivers.toList()
  }

  private fun resolveTypeFqn(typeName: String, packageName: String, importMap: Map<String, String>): String? =
    when {
      '.' in typeName -> typeName
      typeName in importMap -> importMap.getValue(typeName)
      else -> "$packageName.$typeName"
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

  private fun findDirectedCycles(edges: Map<String, Set<String>>): List<List<String>> {
    val cycles = linkedSetOf<List<String>>()
    val nodes = (edges.keys + edges.values.flatten()).toSet()
    fun normalizeCycle(path: List<String>): List<String> {
      if (path.isEmpty()) return path
      val start = path.indices.minByOrNull { index -> path[index] } ?: return path.sorted()
      return (path.drop(start) + path.take(start)).sorted()
    }
    fun dfs(node: String, path: MutableList<String>, visiting: MutableSet<String>) {
      if (node in visiting) {
        val cycleStart = path.indexOf(node)
        if (cycleStart >= 0) {
          cycles += normalizeCycle(path.subList(cycleStart, path.size))
        }
        return
      }
      visiting += node
      path += node
      (edges[node] ?: emptySet()).forEach { neighbor -> dfs(neighbor, path, visiting) }
      visiting -= node
      path.removeAt(path.lastIndex)
    }
    nodes.forEach { node -> dfs(node, mutableListOf(), mutableSetOf()) }
    return cycles.toList()
  }

  data class AuthoredSuppression(val relativePath: String, val symbol: String, val rule: String)

  val COMPLEXITY_SUPPRESSION_RULES: Set<String> = setOf(
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

  val suppressionScanRoots: List<String> = listOf(
    "runtime-kotlin",
    "runtime-kotlin/build-logic",
  )

  fun parseSuppressionAllowList(decisionsMarkdown: String): Set<Triple<String, String, String>> {
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

  fun authoredSuppressions(scanRoots: List<String> = suppressionScanRoots): List<AuthoredSuppression> =
    scanRoots.flatMap { scanRoot ->
      kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
        .filter { path -> !path.toString().replace('\\', '/').contains("/generated/") }
        .flatMap { sourceFile ->
          val normalized = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
          val relativePath = normalized.removePrefix("runtime-kotlin/")
          authoredSuppressionsFromFile(relativePath, sourceFile.readText())
        }
    }

  fun authoredSuppressionsFromFile(relativePath: String, source: String): List<AuthoredSuppression> =
    AuthoredSuppressionScanner.scan(relativePath, source.lineSequence())

  fun authoredSuppressionsInSource(relativePath: String, source: String): List<AuthoredSuppression> =
    AuthoredSuppressionScanner.scan(relativePath, source.lineSequence())

  fun suppressionViolations(
    suppressions: List<AuthoredSuppression>,
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

  fun detektComplexityPinViolations(detektYaml: String): List<String> =
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
}
