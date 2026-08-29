package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ArchitectureScanSupport {
  val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
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

  fun declaredPackage(source: String): String? =
    PACKAGE_PATTERN.find(source)?.groupValues?.get(1)

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

  fun packageClusteringViolations(
    sourceRoots: List<String>,
    genericSegments: Set<String>,
  ): List<String> {
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
        if (relativePath.contains("/src/test/")) return@forEach
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

  fun inlineFqnViolations(
    scanRoots: List<String>,
    prefixes: List<String>,
  ): List<String> {
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
        violations += "$relativePath re-applies $settingName already owned by configureKotlinJvm via skillbill.jvm-library."
      }
    }
    return violations.sorted()
  }

  fun inlineFqnReferences(source: String, prefixes: List<String>): List<String> =
    sourceWithoutStringLiterals(source)
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
          bodies[capturingName!!] = capture.toString()
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
      val lineComment = remaining.indexOf("//").takeUnless { index -> index == -1 } ?: remaining.length
      val blockComment = remaining.indexOf("/*").takeUnless { index -> index == -1 } ?: remaining.length
      if (lineComment <= blockComment) {
        output.append(remaining.take(lineComment))
        break
      }
      output.append(remaining.take(blockComment))
      val end = remaining.indexOf("*/", blockComment + 2)
      if (end == -1) break
      remaining = remaining.drop(end + 2)
    }
    return SourceLine(output.toString())
  }

  private val PACKAGE_PATTERN = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
  private val TOP_LEVEL_DECLARATION_PATTERN =
    Regex(
      """^\s*((?:(?:public|internal|private|protected|abstract|sealed|open|final|data|enum|value|fun)\s+)*)""" +
        """(?:class|object|interface|fun)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
    )
  private val FUNCTION_PATTERN = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
  private val CAMEL_TOKEN_PATTERN = Regex("""[A-Z]?[a-z]+|[A-Z]+(?=[A-Z][a-z]|\b)""")
}
