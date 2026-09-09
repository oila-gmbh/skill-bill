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

  fun scanSourceFiles(files: List<SourceFile>, includePayloadKeyAccesses: Boolean = false): WireVocabularyScanResult {
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
    return enumDeclarations(file, source) + objectDeclarations(file, source)
  }

  private fun enumDeclarations(file: SourceFile, source: String): List<WireVocabularyDeclaration> {
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
      declarations += aliasDeclarations(file, body, owner, openBrace + 1, declarations)
    }
    return declarations
  }

  private fun aliasDeclarations(
    file: SourceFile,
    body: String,
    owner: String,
    bodyOffset: Int,
    tokens: List<WireVocabularyDeclaration>,
  ): List<WireVocabularyDeclaration> {
    val aliases = mutableListOf<WireVocabularyDeclaration>()
    wireVocabularyFromWirePattern.findAll(body).forEach { decoder ->
      val decoderOpenBrace = body.indexOf('{', decoder.range.first)
      val decoderCloseBrace = matchingDelimiter(body, decoderOpenBrace, '{', '}')
      if (decoderOpenBrace < 0 || decoderCloseBrace < 0) return@forEach
      val decoderBody = body.substring(decoderOpenBrace + 1, decoderCloseBrace)
      val decoderOffset = bodyOffset + decoderOpenBrace + 1
      wireVocabularyAliasPattern.findAll(decoderBody).forEach { alias ->
        aliasDeclaration(tokens, file, owner, alias.groupValues[1], decoderOffset + alias.range.first)
          ?.let { aliases += it }
      }
      wireVocabularyAliasSetPattern.findAll(decoderBody).forEach { aliasSet ->
        Regex(""""([^"\n]+)"""").findAll(aliasSet.groupValues[1]).forEach { alias ->
          val offset = decoderOffset + aliasSet.range.first + alias.range.first
          aliasDeclaration(tokens, file, owner, alias.groupValues[1], offset)?.let { aliases += it }
        }
      }
    }
    return aliases
  }

  private fun aliasDeclaration(
    tokens: List<WireVocabularyDeclaration>,
    file: SourceFile,
    owner: String,
    value: String,
    offset: Int,
  ): WireVocabularyDeclaration? = if (tokens.any { it.category == "token" && it.value == value && it.owner == owner }) {
    null
  } else {
    declaration("alias", value, owner, file, offset)
  }

  private fun objectDeclarations(file: SourceFile, source: String): List<WireVocabularyDeclaration> {
    val declarations = mutableListOf<WireVocabularyDeclaration>()
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
          .filter { owner -> sharesDecodingContext(file, source, owner) }
        if (owners.isNotEmpty()) {
          violations += file.relativePath + ":" + lineOf(source, start) + " " + name +
            " restates '" + value + "'; owner(s): " + owners.joinToString()
        }
      }
    }
    return violations
  }

  private fun sharesDecodingContext(file: SourceFile, source: String, owner: String): Boolean =
    owner.substringBeforeLast('.') == file.packageName ||
      Regex("\\b" + Regex.escape(owner.substringAfterLast('.')) + "\\b").containsMatchIn(source)

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
        if (openParen < 0 || closeParen < 0) {
          null
        } else {
          Triple(name, match.range.first, source.substring(openParen + 1, closeParen))
        }
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

  private fun withoutComments(source: String): String = CommentStripper(source).strip()

  private fun matchingDelimiter(source: String, openIndex: Int, open: Char, close: Char): Int {
    if (openIndex < 0) return -1
    var depth = 0
    var inString = false
    var escaped = false
    var index = openIndex
    while (index < source.length) {
      val current = source[index]
      if (inString) {
        if (escaped) {
          escaped = false
        } else if (current == '\\') {
          escaped = true
        } else if (current == '"') {
          inString = false
        }
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

private class CommentStripper(private val source: String) {
  private enum class Mode { CODE, LINE_COMMENT, BLOCK_COMMENT, TRIPLE_STRING, STRING, CHARACTER }

  private val output = StringBuilder(source.length)
  private var index = 0
  private var mode = Mode.CODE
  private var escaped = false

  fun strip(): String {
    while (index < source.length) {
      index += when (mode) {
        Mode.LINE_COMMENT -> consumeLineComment()
        Mode.BLOCK_COMMENT -> consumeBlockComment()
        Mode.TRIPLE_STRING -> consumeTripleString()
        Mode.STRING -> consumeQuoted('"')
        Mode.CHARACTER -> consumeQuoted('\'')
        Mode.CODE -> consumeCode()
      }
    }
    return output.toString()
  }

  private fun consumeLineComment(): Int {
    val current = source[index]
    if (current == '\n') {
      mode = Mode.CODE
      output.append(current)
    } else {
      output.append(' ')
    }
    return 1
  }

  private fun consumeBlockComment(): Int {
    if (source[index] == '*' && source.getOrNull(index + 1) == '/') {
      output.append("  ")
      mode = Mode.CODE
      return 2
    }
    output.append(if (source[index] == '\n') '\n' else ' ')
    return 1
  }

  private fun consumeTripleString(): Int {
    output.append(source[index])
    if (!source.startsWith("\"\"\"", index)) return 1
    output.append("\"\"")
    mode = Mode.CODE
    return 3
  }

  private fun consumeQuoted(terminator: Char): Int {
    val current = source[index]
    output.append(current)
    when {
      escaped -> escaped = false
      current == '\\' -> escaped = true
      current == terminator -> mode = Mode.CODE
    }
    return 1
  }

  private fun consumeCode(): Int {
    val current = source[index]
    val next = source.getOrNull(index + 1)
    return when {
      current == '/' && next == '/' -> enter(Mode.LINE_COMMENT, "  ", 2)
      current == '/' && next == '*' -> enter(Mode.BLOCK_COMMENT, "  ", 2)
      current == '"' && source.startsWith("\"\"\"", index) -> enter(Mode.TRIPLE_STRING, "\"\"\"", 3)
      current == '"' -> enter(Mode.STRING, "\"", 1)
      current == '\'' -> enter(Mode.CHARACTER, "'", 1)
      else -> {
        output.append(current)
        1
      }
    }
  }

  private fun enter(next: Mode, emit: String, width: Int): Int {
    output.append(emit)
    mode = next
    return width
  }
}
