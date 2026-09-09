package skillbill.architecture

private val wireVocabularyEnumPattern = Regex("""enum\s+class\s+([A-Za-z0-9_]+)([^\{]*)\{""")
private val wireVocabularyObjectPattern = Regex("""object\s+([A-Za-z0-9_]*(?:Keys|PayloadKeys))\s*\{""")
private val wireVocabularyEnumEntryPattern = Regex("""(?m)^\s*[A-Z][A-Z0-9_]*\s*\(\s*"([^"]+)"""")
private val wireVocabularyFromWirePattern = Regex("""fun\s+fromWire(?:Value|Name)?\s*\([^)]*\)[^{]*\{""")
private val wireVocabularyAliasPattern = Regex(""""([^"\n]+)"\s*(?:->|to)""")
private val wireVocabularyAliasSetPattern = Regex("""\bin\s+setOf\s*\(([^)]*)\)\s*->""")
private val wireVocabularyConstPattern = Regex("""\bconst\s+val\s+([A-Za-z0-9_]+)\s*=\s*"([^"]+)"""")

internal data class WireVocabularyDeclaration(
  val category: String,
  val value: String,
  val owner: String,
  val relativePath: String,
  val line: Int,
)

internal data class WireVocabularyScanResult(
  val declarations: List<WireVocabularyDeclaration>,
  val violations: List<String>,
  val baselineViolationCount: Int,
  val remainingViolationCount: Int,
)

internal object WireVocabularyArchitectureSupport {
  fun scanRuntimeMainSources(): WireVocabularyScanResult = scanSourceFiles(
    RuntimeModuleCatalog.declaredGradleModules
      .flatMap { moduleName -> mainSourceRoots(moduleName) }
      .flatMap(::sourceFilesIn),
    includePayloadKeyAccesses = false,
  )

  fun scanSourceFiles(
    files: List<SourceFile>,
    includePayloadKeyAccesses: Boolean = false,
  ): WireVocabularyScanResult {
    val declarations = files.flatMap(::declarationsIn).sortedWith(
      compareBy<WireVocabularyDeclaration> { it.category }
        .thenBy { it.value }
        .thenBy { it.owner }
        .thenBy { it.relativePath }
        .thenBy { it.line },
    )
    val tokenValues = declarations
      .filter { it.category == "token" || it.category == "alias" }
      .map { it.value }
      .toSet()
    val keyValues = declarations.filter { it.category == "key" }.map { it.value }.toSet()
    val violations = buildList {
      addAll(duplicateDeclarations(declarations))
      files.forEach { file ->
        addAll(localVocabularyRestatements(file, tokenValues, declarations))
        if (includePayloadKeyAccesses) addAll(payloadKeyAccesses(file, keyValues, declarations))
      }
    }.distinct().sorted()
    return WireVocabularyScanResult(
      declarations = declarations,
      violations = violations,
      baselineViolationCount = violations.size,
      remainingViolationCount = violations.size,
    )
  }

  fun vocabularyDelta(before: WireVocabularyScanResult, after: WireVocabularyScanResult): Int =
    before.remainingViolationCount - after.remainingViolationCount

  private fun declarationsIn(file: SourceFile): List<WireVocabularyDeclaration> {
    val source = withoutComments(file.source)
    val declarations = mutableListOf<WireVocabularyDeclaration>()
    wireVocabularyEnumPattern.findAll(source).forEach { match ->
      val openBrace = source.indexOf('{', match.range.first)
      val closeBrace = matchingDelimiter(source, openBrace, '{', '}')
      if (openBrace < 0 || closeBrace < 0) return@forEach
      val header = match.groupValues[2]
      val body = source.substring(openBrace + 1, closeBrace)
      val owner = file.packageName + "." + match.groupValues[1]
      if (header.contains("wireValue") || body.contains("wireValue")) {
        wireVocabularyEnumEntryPattern.findAll(body.substringBefore("companion object")).forEach { entry ->
          declarations += declaration("token", entry.groupValues[1], owner, file, openBrace + 1 + entry.range.first)
        }
      }
      wireVocabularyFromWirePattern.findAll(body).forEach { decoder ->
        val decoderOpenBrace = body.indexOf('{', decoder.range.first)
        val decoderCloseBrace = matchingDelimiter(body, decoderOpenBrace, '{', '}')
        if (decoderOpenBrace < 0 || decoderCloseBrace < 0) return@forEach
        val decoderBody = body.substring(decoderOpenBrace + 1, decoderCloseBrace)
        wireVocabularyAliasPattern.findAll(decoderBody).forEach { alias ->
          val value = alias.groupValues[1]
          if (declarations.none { it.category == "token" && it.value == value && it.owner == owner }) {
            declarations += declaration(
              "alias",
              value,
              owner,
              file,
              openBrace + 1 + decoderOpenBrace + 1 + alias.range.first,
            )
          }
        }
        wireVocabularyAliasSetPattern.findAll(decoderBody).forEach { aliases ->
          Regex(""""([^"\n]+)"""").findAll(aliases.groupValues[1]).forEach { alias ->
            val value = alias.groupValues[1]
            if (declarations.none { it.category == "token" && it.value == value && it.owner == owner }) {
              declarations += declaration(
                "alias",
                value,
                owner,
                file,
                openBrace + 1 + decoderOpenBrace + 1 + aliases.range.first + alias.range.first,
              )
            }
          }
        }
      }
    }
    wireVocabularyObjectPattern.findAll(source).forEach { match ->
      val openBrace = source.indexOf('{', match.range.first)
      val closeBrace = matchingDelimiter(source, openBrace, '{', '}')
      if (openBrace < 0 || closeBrace < 0) return@forEach
      val owner = file.packageName + "." + match.groupValues[1]
      wireVocabularyConstPattern.findAll(source.substring(openBrace + 1, closeBrace)).forEach { key ->
        declarations += declaration("key", key.groupValues[2], owner, file, openBrace + 1 + key.range.first)
      }
    }
    return declarations
  }

  private fun duplicateDeclarations(declarations: List<WireVocabularyDeclaration>): List<String> =
    declarations.groupBy { declaration ->
      if (declaration.category == "key") {
        Triple(declaration.category, declaration.value, "")
      } else {
        Triple(declaration.category, declaration.value, declaration.owner)
      }
    }
      .filterValues { it.size > 1 }
      .values
      .flatMap { duplicates ->
        val first = duplicates.first()
        listOf(
          "duplicate " + first.category + " '" + first.value + "' owned by " + first.owner + ": " +
            duplicates.joinToString { location(it) },
        )
      }

  private fun localVocabularyRestatements(
    file: SourceFile,
    tokenValues: Set<String>,
    declarations: List<WireVocabularyDeclaration>,
  ): List<String> {
    val violations = mutableListOf<String>()
    val source = withoutComments(file.source)
    collectionLiteralBodies(source).forEach { (name, start, body) ->
      collectionValues(name, body).filter { it in tokenValues }.forEach { value ->
        val owners = declarations
          .filter { it.value == value && it.category in setOf("token", "alias") }
          .map { it.owner }
          .distinct()
        if (owners.isNotEmpty()) {
          violations += file.relativePath + ":" + lineOf(source, start) + " " + name +
            " restates '" + value + "'; owner(s): " + owners.joinToString()
        }
      }
    }
    return violations
  }

  private fun payloadKeyAccesses(
    file: SourceFile,
    keyValues: Set<String>,
    declarations: List<WireVocabularyDeclaration>,
  ): List<String> {
    val violations = mutableListOf<String>()
    val source = withoutComments(file.source)
    keyValues.forEach { value ->
      val accessPatterns = listOf(
        Regex("""\[\s*""" + "\"" + Regex.escape(value) + "\"" + """\s*]"""),
        Regex("""\b(?:get|put|getString|setString)\s*\(\s*""" + "\"" + Regex.escape(value) + "\""),
        Regex("""@(?:SerialName|JsonProperty)\s*\(\s*""" + "\"" + Regex.escape(value) + "\""),
        Regex("""(?:^|[({,])\s*""" + "\"" + Regex.escape(value) + "\"" + """\s+to\b"""),
      )
      accessPatterns.flatMap { it.findAll(source).toList() }.forEach { access ->
        val owners = declarations.filter { it.category == "key" && it.value == value }
          .map { it.owner }
          .distinct()
        if (owners.none { owner -> isInsideOwnedKeyObject(source, access.range.first, owner) }) {
          violations += file.relativePath + ":" + lineOf(source, access.range.first) +
            " accesses key '" + value + "'; owner(s): " + owners.joinToString()
        }
      }
    }
    return violations
  }

  private fun isInsideOwnedKeyObject(source: String, offset: Int, owner: String): Boolean {
    val objectName = owner.substringAfterLast('.')
    return Regex("""object\s+""" + Regex.escape(objectName) + """\s*\{""").findAll(source).any { match ->
      val openBrace = source.indexOf('{', match.range.first)
      val closeBrace = matchingDelimiter(source, openBrace, '{', '}')
      openBrace >= 0 && closeBrace >= 0 && offset > openBrace && offset < closeBrace
    }
  }

  private fun collectionLiteralBodies(source: String): List<Triple<String, Int, String>> =
    listOf("setOf", "mapOf").flatMap { name ->
      Regex("""\b""" + name + """\s*\(""").findAll(source).mapNotNull { match ->
        val openParen = source.indexOf('(', match.range.first)
        val closeParen = matchingDelimiter(source, openParen, '(', ')')
        if (openParen < 0 || closeParen < 0) null
        else Triple(name, match.range.first, source.substring(openParen + 1, closeParen))
      }
    }

  private fun bodyStringLiterals(body: String): List<String> =
    Regex(""""([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()

  private fun collectionValues(name: String, body: String): List<String> = if (name == "setOf") {
    bodyStringLiterals(body)
  } else {
    Regex("""(?:^|,)\s*"([^"]+)"\s+to""").findAll(body).map { it.groupValues[1] }.toList()
  }

  private fun declaration(
    category: String,
    value: String,
    owner: String,
    file: SourceFile,
    offset: Int,
  ): WireVocabularyDeclaration = WireVocabularyDeclaration(
    category = category,
    value = value,
    owner = owner,
    relativePath = file.relativePath,
    line = lineOf(file.source, offset),
  )

  private fun location(declaration: WireVocabularyDeclaration): String =
    declaration.relativePath + ":" + declaration.line

  private fun lineOf(source: String, offset: Int): Int =
    source.take(offset.coerceAtMost(source.length)).count { it == '\n' } + 1

  private fun withoutComments(source: String): String {
    val output = StringBuilder(source.length)
    var index = 0
    var lineComment = false
    var blockComment = false
    var string = false
    var tripleString = false
    var character = false
    var escaped = false
    while (index < source.length) {
      val current = source[index]
      val next = source.getOrNull(index + 1)
      when {
        lineComment -> {
          if (current == '\n') {
            lineComment = false
            output.append(current)
          } else {
            output.append(' ')
          }
        }
        blockComment -> {
          if (current == '*' && next == '/') {
            output.append("  ")
            index += 1
            blockComment = false
          } else {
            output.append(if (current == '\n') '\n' else ' ')
          }
        }
        tripleString -> {
          output.append(current)
          if (source.startsWith("\"\"\"", index)) {
            output.append("\"\"")
            index += 2
            tripleString = false
          }
        }
        string -> {
          output.append(current)
          if (escaped) escaped = false
          else if (current == '\\') escaped = true
          else if (current == '"') string = false
        }
        character -> {
          output.append(current)
          if (escaped) escaped = false
          else if (current == '\\') escaped = true
          else if (current == '\'') character = false
        }
        current == '/' && next == '/' -> {
          output.append("  ")
          index += 1
          lineComment = true
        }
        current == '/' && next == '*' -> {
          output.append("  ")
          index += 1
          blockComment = true
        }
        current == '"' && source.startsWith("\"\"\"", index) -> {
          output.append("\"\"\"")
          index += 2
          tripleString = true
        }
        current == '"' -> {
          output.append(current)
          string = true
        }
        current == '\'' -> {
          output.append(current)
          character = true
        }
        else -> output.append(current)
      }
      index += 1
    }
    return output.toString()
  }

  private fun matchingDelimiter(source: String, openIndex: Int, open: Char, close: Char): Int {
    if (openIndex < 0) return -1
    var depth = 0
    var inString = false
    var escaped = false
    var index = openIndex
    while (index < source.length) {
      val current = source[index]
      if (inString) {
        if (escaped) escaped = false
        else if (current == '\\') escaped = true
        else if (current == '"') inString = false
      } else if (current == '"') {
        inString = true
      } else if (current == open) {
        depth += 1
      } else if (current == close) {
        depth -= 1
        if (depth == 0) return index
      }
      index += 1
    }
    return -1
  }
}
