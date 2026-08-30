package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val runtimeArchitectureRoot: Path =
  Path.of("").toAbsolutePath().normalize().let { workingDir ->
    if (workingDir.fileName.toString().startsWith("runtime-")) {
      workingDir.parent
    } else {
      workingDir
    }
  }

internal val runtimeArchitectureSourceRoots: List<Path> =
  listOf(
    runtimeArchitectureRoot.resolve("runtime-application/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-contracts/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-core/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-domain/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-infra-fs/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-infra-http/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-infra-sqlite/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-cli/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-mcp/src/main/kotlin"),
    runtimeArchitectureRoot.resolve("runtime-ports/src/main/kotlin"),
  )

internal const val MCP_SCAFFOLD_RUNTIME_PATH =
  "runtime-mcp/src/main/kotlin/skillbill/mcp/scaffold/McpScaffoldRuntime.kt"

internal fun assertRegularFiles(relativePaths: List<String>, present: Boolean) {
  relativePaths.forEach { relative ->
    val path = runtimeArchitectureRoot.resolve(relative)
    if (present) {
      assertTrue(Files.isRegularFile(path), "Missing infra-fs-owned validator: $relative")
    } else {
      assertTrue(!Files.exists(path), "Legacy contract/domain validator shim must stay absent: $relative")
    }
  }
}
internal fun syntheticSourceFile(relativePath: String, source: String): SourceFile = SourceFile(
  relativePath = relativePath,
  packageName = RuntimeArchitectureScanConstants.packagePattern.find(source)?.groupValues?.get(1).orEmpty(),
  imports = RuntimeArchitectureScanConstants.importPattern.findAll(source)
    .map { it.groupValues[1].substringBefore(" as ") }
    .toList(),
  source = source,
)

internal fun installPortFunctionSignatures(sourceFile: SourceFile): List<InstallPortFunctionSignature> {
  val lines = sourceFile.source.lines()
  return lines.mapIndexedNotNull { index, line ->
    val match = RuntimeArchitectureScanConstants.portFunctionStartPattern.find(line.trim())
      ?: return@mapIndexedNotNull null
    val signatureText = collectFunctionSignature(lines, index)
    val parsed = RuntimeArchitectureScanConstants.portFunctionSignaturePattern.find(signatureText)
    val functionName = match.groupValues[1]
    val parameters = parsed?.groupValues?.get(2).orEmpty().trim()
    val returnType = parsed?.groupValues?.get(3).orEmpty()
    val parameterTypes = parameters.split(",")
      .map(String::trim)
      .filter(String::isNotBlank)
      .map { parameter -> parameter.substringAfter(":").trim().substringAfterLast(".") }
    InstallPortFunctionSignature(
      sourcePath = sourceFile.relativePath,
      functionName = functionName,
      parameters = parameters,
      returnType = returnType,
      hasSingleRequestParameter = parameterTypes.size == 1 && parameterTypes.single().endsWith("Request"),
      hasResultReturn = returnType.substringBefore("<").substringAfterLast(".").endsWith("Result"),
    )
  }
}
internal fun collectFunctionSignature(lines: List<String>, startIndex: Int): String {
  val signature = StringBuilder()
  var openParens = 0
  var sawParen = false
  var index = startIndex
  var shouldStop = false
  while (index < lines.size && !shouldStop) {
    val current = lines[index]
    signature.append(current.trim()).append(' ')
    current.forEach { char ->
      when (char) {
        '(' -> {
          openParens += 1
          sawParen = true
        }
        ')' -> openParens -= 1
      }
    }
    if (sawParen && openParens == 0) {
      val text = signature.toString()
      shouldStop = hasFunctionSignatureTerminator(text)
      val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
      if (!nextLine.startsWith(":")) shouldStop = true
    }
    index += 1
  }
  return signature.toString()
}
internal fun hasFunctionSignatureTerminator(text: String): Boolean =
  containsReturnTypeSeparator(text) || " =" in text || text.trim().endsWith("{")

internal fun containsReturnTypeSeparator(text: String): Boolean = "):" in text || ") :" in text

internal fun assertInventoryCategoriesKnown(inventory: Skill522Inventory) {
  val knownCategories = setOf(
    "must_type_now",
    "open_extension",
    "private_serializer",
    "postponed_with_reason",
  )
  val unknownCategories = inventory.entries.map { it.category }.toSet() - knownCategories
  assertTrue(
    unknownCategories.isEmpty(),
    "SKILL-52.2 inventory contains unknown categories: $unknownCategories. " +
      "Allowed: $knownCategories.",
  )
}
internal fun assertInventoryMatchesAllowList(inventory: Skill522Inventory) {
  val inventoryFqns = inventory.entries.map { it.fqn }.toSet()
  val allowList = RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST.toSet()
  val missingFromInventory = allowList - inventoryFqns
  val unknownInInventory = inventoryFqns - allowList
  assertTrue(
    missingFromInventory.isEmpty() && unknownInInventory.isEmpty(),
    "SKILL-52.2 inventory must classify every entry in " +
      "RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST " +
      "exactly once.\nMissing from inventory: $missingFromInventory\n" +
      "Inventory entries not in allow-list: $unknownInInventory",
  )
}
internal fun assertInventoryHasNoDuplicateFqns(inventory: Skill522Inventory) {
  val duplicates = inventory.entries.groupBy { it.fqn }
    .filterValues { it.size > 1 }
    .keys
  assertTrue(
    duplicates.isEmpty(),
    "SKILL-52.2 inventory must classify every FQN exactly once. Duplicates: $duplicates",
  )
}
internal fun assertAnnotatedDeclarationsAreOpenExtension(inventory: Skill522Inventory) {
  val annotatedFqns = sourceFiles()
    .filter { file ->
      file.relativePath.startsWith("runtime-application/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-domain/src/main/kotlin/") ||
        file.relativePath.startsWith("runtime-ports/src/main/kotlin/")
    }
    .flatMap(::findAnnotatedOpenBoundaryDeclarations)
    .toSet()
  val openExtensionFqns = inventory.entries
    .filter { it.category == "open_extension" }
    .map { it.fqn }
    .toSet()
  val annotatedNotOpenExtension = annotatedFqns - openExtensionFqns
  assertTrue(
    annotatedNotOpenExtension.isEmpty(),
    "Every @OpenBoundaryMap-annotated declaration in runtime-application/-domain/-ports " +
      "MUST be classified under SKILL-52.2 inventory category `open_extension`.\n" +
      "Misclassified: $annotatedNotOpenExtension",
  )
}
internal fun assertSubtaskIdsPresentForGatedCategories(inventory: Skill522Inventory) {
  val needsSubtaskCategories = setOf("must_type_now", "postponed_with_reason")
  val missingSubtask = inventory.entries
    .filter { it.category in needsSubtaskCategories }
    .filter { it.subtaskId == null || it.subtaskId !in 2..5 }
    .map { "${it.fqn} (category=${it.category}, subtaskId=${it.subtaskId})" }
  assertTrue(
    missingSubtask.isEmpty(),
    "Every SKILL-52.2 inventory entry under $needsSubtaskCategories MUST carry a " +
      "[subtask N] tag with N in 2..5.\nNon-compliant:\n" +
      missingSubtask.joinToString(separator = "\n"),
  )
}
internal fun parseSkill522Inventory(architecture: String): Skill522Inventory {
  val body = extractSkill522InventoryBody(architecture)
  val rawLines = body.lines()
  val state = InventoryParseState()
  while (state.index < rawLines.size) {
    state.index = advanceInventoryCursor(rawLines, state)
  }
  return Skill522Inventory(entries = state.entries)
}
internal fun advanceInventoryCursor(rawLines: List<String>, state: InventoryParseState): Int {
  val index = state.index
  val trimmed = rawLines[index].trim()
  val heading = RuntimeArchitectureScanConstants.INVENTORY_HEADING_PATTERN.find(trimmed)?.groupValues?.get(1)
  if (heading != null) {
    state.currentCategory = heading
  }
  val bulletMatch = if (heading == null) {
    RuntimeArchitectureScanConstants.INVENTORY_BULLET_PATTERN.find(trimmed)
  } else {
    null
  }
  val entry = buildInventoryEntry(state.currentCategory, bulletMatch, rawLines, index)
  if (entry != null) {
    state.entries += entry.entry
  }
  return entry?.nextIndex ?: (index + 1)
}
internal class InventoryParseState(
  var index: Int = 0,
  var currentCategory: String? = null,
  val entries: MutableList<Skill522InventoryEntry> = mutableListOf(),
)

internal fun extractSkill522InventoryBody(architecture: String): String {
  val sectionStart = architecture.indexOf("<!-- skill-52-2-inventory:start -->")
  val sectionEnd = architecture.indexOf("<!-- skill-52-2-inventory:end -->")
  require(sectionStart >= 0 && sectionEnd > sectionStart) {
    "ARCHITECTURE.md must declare a SKILL-52.2 inventory section bracketed by " +
      "'<!-- skill-52-2-inventory:start -->' / '<!-- skill-52-2-inventory:end -->' " +
      "machine-readable markers."
  }
  return architecture.substring(sectionStart, sectionEnd)
}
internal fun buildInventoryEntry(
  category: String?,
  bulletMatch: MatchResult?,
  rawLines: List<String>,
  index: Int,
): InventoryEntryWithCursor? {
  if (category == null || bulletMatch == null) return null
  val (lookahead, joinedTail) = consumeContinuationLines(
    rawLines = rawLines,
    startIndex = index + 1,
    head = bulletMatch.groupValues[2],
  )
  val subtaskId = RuntimeArchitectureScanConstants.INVENTORY_SUBTASK_PATTERN
    .find(joinedTail)
    ?.groupValues
    ?.get(1)
    ?.toIntOrNull()
  return InventoryEntryWithCursor(
    entry = Skill522InventoryEntry(
      fqn = bulletMatch.groupValues[1],
      category = category,
      subtaskId = subtaskId,
    ),
    nextIndex = lookahead,
  )
}

internal fun consumeContinuationLines(rawLines: List<String>, startIndex: Int, head: String): Pair<Int, String> {
  val accumulator = StringBuilder(head)
  val end = rawLines
    .asSequence()
    .drop(startIndex)
    .takeWhile { line -> isInventoryContinuationLine(line) }
    .onEach { line -> accumulator.append(' ').append(line.trim()) }
    .count() + startIndex
  return end to accumulator.toString()
}
internal fun isInventoryContinuationLine(line: String): Boolean {
  val trimmed = line.trim()
  val isTerminator = trimmed.isEmpty() ||
    RuntimeArchitectureScanConstants.INVENTORY_BULLET_LEADER_PATTERN.containsMatchIn(line) ||
    RuntimeArchitectureScanConstants.INVENTORY_HEADING_PATTERN.containsMatchIn(trimmed)
  return !isTerminator
}
internal data class InventoryEntryWithCursor(
  val entry: Skill522InventoryEntry,
  val nextIndex: Int,
)

internal data class Skill522InventoryEntry(
  val fqn: String,
  val category: String,
  val subtaskId: Int?,
)

internal data class Skill522Inventory(
  val entries: List<Skill522InventoryEntry>,
)

internal fun rawMapViolationFixtureSource(): String = """
    package skillbill.application

    typealias AnyMapAlias = Map<String, Any>
    typealias HashMapAlias = HashMap<String, Any?>

    class Fake {
      public fun foo(): Map<String, Any?> = emptyMap()

      public fun nonNullMap(): Map<String, Any> = emptyMap()

      fun bar(): Map<String, *> = emptyMap<String, Any?>()

      fun baz(input: MutableMap<String, Any?>) { input.clear() }

      fun mutableNonNull(input: MutableMap<String, Any>) { input.clear() }

      fun mutableStar(input: MutableMap<String, *>) {}

      fun hashMap(input: HashMap<String, Any?>) { input.clear() }

      fun hashMapNonNull(input: HashMap<String, Any>) { input.clear() }

      fun hashMapStar(input: HashMap<String, *>) {}

      fun linkedHashMap(input: LinkedHashMap<String, Any?>) { input.clear() }

      fun linkedHashMapNonNull(input: LinkedHashMap<String, Any>) { input.clear() }

      fun linkedHashMapStar(input: LinkedHashMap<String, *>) {}

      fun aliasMap(input: AnyMapAlias) {}

      fun aliasHashMap(): HashMapAlias = hashMapOf()

      fun multiLine(
        first: String,
      ): Map<String, Any?> = emptyMap()
    }
""".trimIndent()

internal fun expectedRawMapViolationFixtureNames(): List<String> = listOf(
  "aliasHashMap",
  "aliasMap",
  "bar",
  "baz",
  "foo",
  "hashMap",
  "hashMapNonNull",
  "hashMapStar",
  "linkedHashMap",
  "linkedHashMapNonNull",
  "linkedHashMapStar",
  "multiLine",
  "mutableNonNull",
  "mutableStar",
  "nonNullMap",
).sorted()

private val rawMapBannedShapes =
  listOf(
    "Map<String, Any?>",
    "Map<String, Any>",
    "Map<String, *>",
    "HashMap<String, Any?>",
    "HashMap<String, Any>",
    "HashMap<String, *>",
    "LinkedHashMap<String, Any?>",
    "LinkedHashMap<String, Any>",
    "LinkedHashMap<String, *>",
    "MutableMap<String, Any?>",
    "MutableMap<String, Any>",
    "MutableMap<String, *>",
  )

private val rawMapFunDeclPattern =
  Regex("""^(?:public\s+)?fun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)\s*\(""")

private val rawMapValDeclPattern =
  Regex("""^(?:public\s+)?(?:val|var)\s+([A-Za-z0-9_]+)\s*:""")

private fun rawMapDeclarationName(trimmed: String): String? {
  val funMatch = rawMapFunDeclPattern.find(trimmed)
  val valMatch = rawMapValDeclPattern.find(trimmed)
  return funMatch?.groupValues?.get(2) ?: valMatch?.groupValues?.get(1)
}

private fun collectRawMapDeclarationSignature(lines: List<String>, startIndex: Int, hasValDecl: Boolean): String {
  val signature = StringBuilder()
  var index = startIndex
  var openParens = 0
  var sawParen = false
  var awaitingReturn = false
  while (index < lines.size && index - startIndex <= 30) {
    val current = lines[index]
    signature.append(current).append('\n')
    current.forEach { ch ->
      when (ch) {
        '(' -> {
          openParens += 1
          sawParen = true
        }
        ')' -> openParens -= 1
      }
    }
    val closed = sawParen && openParens == 0
    val stop = rawMapSignatureComplete(current, closed, awaitingReturn, sawParen, hasValDecl)
    if (stop) break
    if (closed) awaitingReturn = true
    index += 1
  }
  return signature.toString()
}

private fun rawMapSignatureComplete(
  current: String,
  closed: Boolean,
  awaitingReturn: Boolean,
  sawParen: Boolean,
  hasValDecl: Boolean,
): Boolean {
  if (closed) {
    val containsReturnMarker = current.contains("):") || current.contains(") :") ||
      current.endsWith(":") || current.contains(" {") || current.endsWith("{") ||
      current.contains(" =") || current.endsWith("= ") || current.endsWith(") = null")
    if (containsReturnMarker || awaitingReturn) return true
  }
  return !sawParen && hasValDecl && current.contains(": ")
}

private fun signatureUsesBannedRawMap(
  sigText: String,
  bannedShapes: List<String>,
  bannedTypeAliases: Set<String>,
): Boolean = bannedShapes.any { shape -> shape in sigText } ||
  bannedTypeAliases.any { alias -> Regex("""\b${Regex.escape(alias)}\b""").containsMatchIn(sigText) }

private data class RawMapAllowlistContext(
  val trimmed: String,
  val sigText: String,
  val precedingLines: List<String>,
  val tracker: ScopeTracker,
  val packageName: String,
  val declName: String,
  val allowlistSet: Set<String>,
)

private fun isAllowlistedRawMapDeclaration(context: RawMapAllowlistContext): Boolean {
  val annotationPrecedingLines = context.precedingLines
    .map(String::trim)
    .takeLastWhile { it.startsWith("@") || it.isEmpty() }
  val annotated = "@OpenBoundaryMap" in context.sigText ||
    annotationPrecedingLines.any { "@OpenBoundaryMap" in it }
  if (annotated) return true
  val nonPublicMarker = Regex("""^(?:private|internal)\s+""").containsMatchIn(context.trimmed) ||
    context.tracker.insideNonPublicScope
  if (nonPublicMarker) return true
  val enclosingPrefix = context.tracker.enclosingStack.joinToString(".").let { if (it.isEmpty()) "" else "$it." }
  val fqn = listOf(context.packageName, "$enclosingPrefix${context.declName}")
    .filter(String::isNotBlank)
    .joinToString(".")
  return fqn in context.allowlistSet
}

internal fun findRawMapViolations(file: SourceFile): List<String> {
  val lines = file.source.lines()
  val bannedTypeAliases = rawMapTypeAliases(file.source, rawMapBannedShapes)
  val violations = mutableListOf<String>()
  val tracker = ScopeTracker()
  val allowlistSet = RuntimeArchitectureScanConstants.RAW_MAP_OPEN_BOUNDARY_ALLOWLIST.toSet()
  lines.forEachIndexed { index, line ->
    tracker.consume(line)
    val trimmed = line.trim()
    val declName = rawMapDeclarationName(trimmed) ?: return@forEachIndexed
    val sigText = collectRawMapDeclarationSignature(
      lines,
      index,
      rawMapValDeclPattern.find(trimmed) != null,
    )
    if (!signatureUsesBannedRawMap(sigText, rawMapBannedShapes, bannedTypeAliases)) return@forEachIndexed
    val precedingLines = lines.subList(maxOf(0, index - 4), index)
    if (isAllowlistedRawMapDeclaration(
        RawMapAllowlistContext(
          trimmed = trimmed,
          sigText = sigText,
          precedingLines = precedingLines,
          tracker = tracker,
          packageName = file.packageName,
          declName = declName,
          allowlistSet = allowlistSet,
        ),
      )
    ) {
      return@forEachIndexed
    }
    val enclosingPrefix = tracker.enclosingStack.joinToString(".").let { if (it.isEmpty()) "" else "$it." }
    val fqn = listOf(file.packageName, "$enclosingPrefix$declName")
      .filter(String::isNotBlank)
      .joinToString(".")
    violations += "${file.relativePath}:${index + 1} public `$declName` exposes raw map shape (fqn=$fqn)"
  }
  return violations
}

internal fun rawMapTypeAliases(source: String, bannedShapes: List<String>): Set<String> {
  val directAliases = mutableMapOf<String, String>()
  val aliasPattern = Regex("""^typealias\s+([A-Za-z0-9_]+)\s*=\s*(.+)$""", RegexOption.MULTILINE)
  aliasPattern.findAll(source).forEach { match ->
    directAliases[match.groupValues[1]] = match.groupValues[2].trim()
  }
  val bannedAliases = mutableSetOf<String>()
  var changed = true
  while (changed) {
    changed = false
    directAliases.forEach { (alias, target) ->
      if (alias !in bannedAliases && (bannedShapes.any { shape -> shape in target } || target in bannedAliases)) {
        bannedAliases += alias
        changed = true
      }
    }
  }
  return bannedAliases
}

private fun openBoundaryDeclarationName(candidate: String): String? {
  val funMatch = rawMapFunDeclPattern.find(candidate)
  val valMatch = rawMapValDeclPattern.find(candidate)
  val classMatch = RuntimeArchitectureScanConstants.scopeDeclarationPattern.find(candidate)
  return funMatch?.groupValues?.get(2)
    ?: valMatch?.groupValues?.get(1)
    ?: classMatch?.groupValues?.get(1)
}

private fun declarationFqn(packageName: String, enclosingStack: ArrayDeque<String>, declName: String): String {
  val enclosingPrefix = enclosingStack.joinToString(".").let { if (it.isEmpty()) "" else "$it." }
  return listOf(packageName, "$enclosingPrefix$declName")
    .filter(String::isNotBlank)
    .joinToString(".")
}

internal fun findAnnotatedOpenBoundaryDeclarations(file: SourceFile): List<String> {
  val lines = file.source.lines()
  val results = mutableListOf<String>()
  val tracker = ScopeTracker()
  lines.forEachIndexed { index, line ->
    tracker.consume(line)
    if (!line.trim().startsWith("@OpenBoundaryMap")) return@forEachIndexed
    val candidate = lines.drop(index + 1)
      .map(String::trim)
      .firstOrNull { it.isNotBlank() && !it.startsWith("@") }
      ?: return@forEachIndexed
    val declName = openBoundaryDeclarationName(candidate) ?: return@forEachIndexed
    results += declarationFqn(file.packageName, tracker.enclosingStack, declName)
  }
  return results
}

internal fun parseArchitectureAllowList(architecture: String): Set<String> {
  val sectionStart = architecture.indexOf("<!-- open-boundary-allowlist:start -->")
  val sectionEnd = architecture.indexOf("<!-- open-boundary-allowlist:end -->")
  require(sectionStart >= 0 && sectionEnd > sectionStart) {
    "ARCHITECTURE.md must declare an Open-Boundary Allow-List section bracketed by " +
      "'<!-- open-boundary-allowlist:start -->' / '<!-- open-boundary-allowlist:end -->' " +
      "machine-readable markers."
  }
  val body = architecture.substring(sectionStart, sectionEnd)
  return Regex("""^\s*-\s+`([A-Za-z0-9_.]+)`""", RegexOption.MULTILINE)
    .findAll(body)
    .map { it.groupValues[1] }
    .toSet()
}

internal class ScopeTracker {
  val enclosingStack: ArrayDeque<String> = ArrayDeque()
  val scopeNonPublic: ArrayDeque<Boolean> = ArrayDeque()
  private val scopeKind: ArrayDeque<Kind> = ArrayDeque()

  private val scopeDepth: ArrayDeque<Int> = ArrayDeque()
  private var braceDepth = 0
  private var parenDepth = 0
  private var pendingScopeName: String? = null
  private var pendingScopeIsData = false
  private var pendingScopeNonPublic = false

  enum class Kind { BRACE, PAREN }

  val insideNonPublicScope: Boolean get() = scopeNonPublic.any { it }

  private var resumeClassName: String? = null
  private var resumeClassNonPublic = false

  fun consume(lineText: String) {
    noteScopeDeclaration(lineText)
    lineText.forEach { ch ->
      when (ch) {
        '{' -> onOpenBrace()
        '}' -> onCloseBrace()
        '(' -> onOpenParen()
        ')' -> onCloseParen()
      }
    }
  }

  internal fun noteScopeDeclaration(lineText: String) {
    val scopeMatch = RuntimeArchitectureScanConstants.scopeDeclarationPattern.find(lineText) ?: return
    pendingScopeName = scopeMatch.groupValues[1]
    pendingScopeIsData = lineText.contains(Regex("""\bdata\s+class\b"""))
    pendingScopeNonPublic = Regex("""^\s*(?:private|internal)\s+""").containsMatchIn(lineText)
    resumeClassName = null
    resumeClassNonPublic = false
  }

  internal fun onOpenBrace() {
    val pendingName = pendingScopeName
    val resumeName = resumeClassName
    when {
      pendingName != null -> {
        pushScope(pendingName, Kind.BRACE, braceDepth, pendingScopeNonPublic)
        pendingScopeName = null
        pendingScopeIsData = false
        pendingScopeNonPublic = false
      }
      resumeName != null && parenDepth == 0 -> {
        pushScope(resumeName, Kind.BRACE, braceDepth, resumeClassNonPublic)
        resumeClassName = null
        resumeClassNonPublic = false
      }
    }
    braceDepth += 1
  }

  internal fun onCloseBrace() {
    braceDepth -= 1
    popScopesWhile(Kind.BRACE) { braceDepth <= it }
  }

  internal fun onOpenParen() {
    val pendingName = pendingScopeName
    if (pendingName != null && pendingScopeIsData) {
      pushScope(pendingName, Kind.PAREN, parenDepth, pendingScopeNonPublic)
      resumeClassName = pendingName
      resumeClassNonPublic = pendingScopeNonPublic
      pendingScopeName = null
      pendingScopeIsData = false
      pendingScopeNonPublic = false
    }
    parenDepth += 1
  }

  internal fun onCloseParen() {
    parenDepth -= 1
    popScopesWhile(Kind.PAREN) { parenDepth <= it }
  }

  internal fun pushScope(name: String, kind: Kind, depth: Int, nonPublic: Boolean) {
    enclosingStack.addLast(name)
    scopeKind.addLast(kind)
    scopeDepth.addLast(depth)
    scopeNonPublic.addLast(nonPublic)
  }

  private inline fun popScopesWhile(kind: Kind, condition: (Int) -> Boolean) {
    while (scopeKind.isNotEmpty() && scopeKind.last() == kind && condition(scopeDepth.last())) {
      scopeKind.removeLast()
      scopeDepth.removeLast()
      enclosingStack.removeLast()
      scopeNonPublic.removeLast()
    }
  }
}

internal fun assertNoBannedImports(files: List<SourceFile>, bannedImports: List<String>) {
  val violations =
    files.flatMap { file ->
      file.imports
        .filter { importedName -> bannedImports.any(importedName::startsWith) }
        .map { importedName -> "${file.relativePath} imports $importedName" }
    }
  assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
}

internal fun assertNoBannedSourceReferences(
  files: List<SourceFile>,
  bannedReferences: List<String>,
  description: String,
) {
  val violations =
    files.flatMap { file ->
      file.source.lines().flatMapIndexed { index, line ->
        bannedReferences
          .filter { reference -> line.containsBannedReference(reference) }
          .map { reference ->
            "${file.relativePath}:${index + 1} contains $description $reference"
          }
      }
    }
  assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
}

internal fun String.containsBannedReference(reference: String): Boolean = if (reference == "Files.") {
  Regex("""\bFiles\.""").containsMatchIn(this)
} else {
  reference in this
}

internal fun assertMcpScaffoldRuntimeOnlyUsesFilesForRepoRootDiscovery(mcpFiles: List<SourceFile>) {
  val scaffoldFile =
    mcpFiles.first { file ->
      file.relativePath == MCP_SCAFFOLD_RUNTIME_PATH
    }
  val filesReferenceLines =
    scaffoldFile.source.lines()
      .filter { line -> "java.nio.file.Files" in line || "Files." in line }
      .map(String::trim)

  assertEquals(
    listOf(
      "import java.nio.file.Files",
      "val hasSettings = Files.isRegularFile(current.resolve(\"runtime-kotlin/settings.gradle.kts\"))",
      "val hasSkills = Files.isDirectory(current.resolve(\"skills\"))",
    ),
    filesReferenceLines,
  )
}

internal fun sourceFiles(): List<SourceFile> = runtimeArchitectureSourceRoots.flatMap { sourceRoot ->
  sourceFilesIn(sourceRoot)
}

internal fun declaredMainSourceFiles(): List<SourceFile> = RuntimeModuleCatalog.declaredGradleModules
  .flatMap { moduleName -> mainSourceRoots(moduleName) }
  .flatMap { sourceRoot -> sourceFilesIn(sourceRoot) }

internal fun innerLayerTestSourceFiles(): List<SourceFile> =
  listOf("runtime-application", "runtime-domain", "runtime-ports")
    .flatMap { moduleName ->
      listOf("src/test/kotlin", "src/repoTest/kotlin", "src/jvmTest/kotlin", "src/commonTest/kotlin")
        .map { sourceSet -> runtimeArchitectureRoot.resolve(moduleName.replace(':', '/')).resolve(sourceSet) }
        .filter(Files::isDirectory)
    }
    .flatMap { sourceRoot -> sourceFilesIn(sourceRoot) }

internal fun mainSourceRoots(moduleName: String): List<Path> {
  val sourceRoot = runtimeArchitectureRoot.resolve(moduleName.replace(':', '/')).resolve("src")
  if (!Files.isDirectory(sourceRoot)) return emptyList()
  return Files.list(sourceRoot).use { stream ->
    stream
      .filter(Files::isDirectory)
      .filter { path -> path.fileName.toString() == "main" || path.fileName.toString().endsWith("Main") }
      .map { path -> path.resolve("kotlin") }
      .filter(Files::isDirectory)
      .toList()
      .sorted()
  }
}

internal fun sourceFilesIn(sourceRoot: Path): List<SourceFile> = Files.walk(sourceRoot).use { stream ->
  stream
    .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
    .map(::sourceFile)
    .toList()
}

internal fun sourceFile(path: Path): SourceFile {
  val source = Files.readString(path)
  return SourceFile(
    relativePath = runtimeArchitectureRoot.relativize(path).toString().replace('\\', '/'),
    packageName = RuntimeArchitectureScanConstants.packagePattern.find(source)?.groupValues?.get(1).orEmpty(),
    imports = RuntimeArchitectureScanConstants.importPattern.findAll(source)
      .map { it.groupValues[1].substringBefore(" as ") }
      .toList(),
    source = source,
  )
}

internal fun sourcePath(relativePath: String): Path = runtimeArchitectureSourceRoots
  .map { sourceRoot -> sourceRoot.resolve(relativePath) }
  .firstOrNull(Files::exists)
  ?: error("Missing source file: $relativePath")

internal data class SourceFile(
  val relativePath: String,
  val packageName: String,
  val imports: List<String>,
  val source: String,
)

internal data class InstallPortFunctionSignature(
  val sourcePath: String,
  val functionName: String,
  val parameters: String,
  val returnType: String,
  val hasSingleRequestParameter: Boolean,
  val hasResultReturn: Boolean,
) {
  fun render(): String = "$sourcePath::$functionName($parameters): ${returnType.ifBlank { "<missing>" }}"
}

internal object RuntimeArchitectureScanConstants {
  val INVENTORY_HEADING_PATTERN: Regex =
    Regex("""^###\s+(must_type_now|open_extension|private_serializer|postponed_with_reason)\b""")
  val INVENTORY_BULLET_PATTERN: Regex =
    Regex("""^\s*-\s+`([A-Za-z0-9_.]+)`(.*)$""")
  val INVENTORY_SUBTASK_PATTERN: Regex = Regex("""\[subtask\s+(\d+)\]""")
  val INVENTORY_BULLET_LEADER_PATTERN: Regex = Regex("""^\s*-\s+""")

  val RAW_MAP_OPEN_BOUNDARY_ALLOWLIST: List<String> = listOf(
    "skillbill.application.workflow.model.DecompositionPlanManifestInput.plan",
    "skillbill.application.workflow.model.DecompositionPlanManifestInput.artifactsPatch",
    "skillbill.application.workflow.model.DecompositionPlanManifestInput.existingArtifacts",
    "skillbill.application.workflow.model.DecompositionManifestWorkflowProjectionInput.artifactsPatch",
    "skillbill.ports.goalrunner.runner.GoalRunnerWorkflowProgressStore.progressEvents",
    "skillbill.workflow.engine.WorkflowEngine.snapshotMap",
    "skillbill.workflow.engine.WorkflowEngine.summaryMap",
    "skillbill.workflow.engine.WorkflowEngine.resumeMap",
    "skillbill.workflow.engine.WorkflowEngine.continueMap",
    "skillbill.workflow.engine.WorkflowEngine.compactContinueMap",
    "skillbill.workflow.engine.WorkflowEngine.updateAcknowledgementMap",
    "skillbill.workflow.engine.WorkflowEngine.inputProjectionMap",
    "skillbill.workflow.engine.model.WorkflowContinuationArtifactSummary.value",
    "skillbill.workflow.engine.model.WorkflowInputProjection.artifacts",
    "skillbill.workflow.engine.WorkflowSnapshotValidator.validate",
    "skillbill.workflow.idestatus.IdeStatusValidator.validate",
    "skillbill.application.idestatus.model.IdeStatusSnapshot.toStatusWireMap",
    "skillbill.application.idestatus.model.IdeStatusProblem.details",
    "skillbill.install.model.InstallPlanWireValidator.validate",
    "skillbill.workflow.decomposition.DecompositionManifestValidator.validate",
    "skillbill.workflow.decomposition.DecompositionManifestValidator.validateYamlText",
    "skillbill.ports.workflow.decomposition.DecompositionManifestFileEncodeStore.encodeManifestYaml",
    "skillbill.workflow.decomposition.DecompositionManifestCodec.decodeMap",
    "skillbill.workflow.decomposition.toWireMap",
    "skillbill.application.decomposition.decodeDecompositionManifestMap",
    "skillbill.application.decomposition.encodeDecompositionManifestMap",
    "skillbill.application.decomposition.DecompositionManifestWriter.writeFromWorkflowUpdate",
    "skillbill.application.decomposition.DecompositionManifestWriter.manifestFromWorkflowUpdate",
    "skillbill.application.decomposition.DecompositionManifestWriter.maybeWriteFromWorkflowUpdate",
    "skillbill.application.workflow.WorkflowFamily.sessionSummary",
    "skillbill.workflow.goal.GoalObservabilityEventValidator.validate",
    "skillbill.workflow.goal.GoalPlanningPreparationEnvelopeValidator.validate",
    "skillbill.review.context.ReviewContextEnvelopeValidator.validate",
    "skillbill.review.context.ReviewContextEnvelopeValidator.validateSpecIntentProjection",
    "skillbill.application.review.model.ReviewContextEnvelope.asWireMap",
    "skillbill.application.review.toBoundedPayload",
    "skillbill.ports.review.model.ReviewAccountingRecord.boundedPayload",
    "skillbill.workflow.goal.model.GoalObservabilityEvent.toArtifactMap",
    "skillbill.workflow.goal.model.GoalObservabilityEvent.toCompactSummaryMap",
    "skillbill.workflow.goal.model.GoalObservabilityHistory.toArtifactList",
    "skillbill.workflow.goal.model.goalObservabilityLatestEventFromArtifacts",
    "skillbill.workflow.goal.model.goalObservabilityHistoryFromArtifacts",
    "skillbill.goalrunner.model.GoalRunnerStatusProjection.latestObservabilityEvent",
    "skillbill.goalrunner.model.GoalRunnerStatusProjectionExtras.latestObservabilityEvent",
    "skillbill.goalrunner.model.GoalRunnerStatusProjector.project",
    "skillbill.workflow.goal.model.GoalProgressEvent.toArtifactMap",
    "skillbill.workflow.goal.model.GoalProgressHistory.toArtifactList",
    "skillbill.goalrunner.model.GoalAttemptLedgerEntry.toArtifactMap",
    "skillbill.goalrunner.model.GoalAttemptLedger.toArtifactList",
    "skillbill.workflow.goal.GoalProgressEventValidator.validate",
    "skillbill.workflow.goal.model.appendBoundedHistoryBySequence",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator.validateAndReadPhaseOutput",
    "skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer.trySynthesize",
    "skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer.envelopeFromSettlement",
    "skillbill.application.featuretask.FeatureTaskPhaseSettlementService.complete",
    "skillbill.application.featuretask.FeatureTaskPhaseSettlementService.block",
    "skillbill.application.featuretask.FeatureTaskPhaseSettlementService.auditSettle",
    "skillbill.application.featuretask.FeatureTaskPhaseSettlementService.findEnvelope",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator.validatePlanningProjection",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator.validateBuildReceipt",
    "skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput.envelope",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint.toEnvelopeMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjection.toEnvelopeMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.toEnvelopeMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope.fromEnvelopeMap",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef.toDeclarationMap",
    "skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration.toArtifactMap",
    "skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement.toTelemetryMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement.toTelemetryMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement.toTelemetryMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement.toTelemetryMap",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeQuarantineValidator.validateQuarantineRecord",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineRecordToWire",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeQuarantineEntriesFromWire",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticSignal.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeDiagnosticSignalsFromWire",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeImplementationAttemptValidator." +
      "validateImplementationAttemptRecord",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptRecordToWire",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeImplementationAttemptsFromWire",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesToArtifact",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointIdentitiesFromArtifact",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffEnvelopeValidator.validateEnvelope",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateDeclaration",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validatePersistenceRecord",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateMeasurement",
    "skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator.validateSharedEvidenceProjection",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalPlanningImport.toArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding.toArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskReviewCompactFinding.fromArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition.toArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFindingVerificationDisposition.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance.fromArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskReviewState.boundedDispositionSummary",
    "skillbill.workflow.goal.model.GoalSubtaskReviewPassResult.toArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskReviewPassResult.fromArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decode",
    "skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly",
    "skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly",
    "skillbill.workflow.goal.model.GoalSubtaskReviewState.toArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskReviewState.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt.validateEntries",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairConstruct.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisturbedRemedy.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisturbedRemedy.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerEntry.toProjectionMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedgerProjection.toProjectionMap",
    "skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting.toArtifactMap",
    "skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting.fromArtifactMap",
    "skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap",
    "skillbill.ports.goalrunner.runner.GoalRunnerTerminalOutcomeStore.recoverMissingResultPrefixOutput",
    "skillbill.workflow.taskruntime.model.toArtifactMap",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeDecomposePlanOutcomeOrNull",
    "skillbill.workflow.taskruntime.model.featureTaskRuntimeIsDecompositionPackage",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap",
    "skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing.toArtifactMap",
    "skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateRunRecord.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationGateProgress.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress.toArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress.fromArtifactMap",
    "skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory.fromMap",
    "skillbill.application.telemetry.lifecycleOkPayload",
    "skillbill.application.telemetry.lifecycleSkippedPayload",
    "skillbill.application.telemetry.lifecycleErrorPayload",
    "skillbill.application.telemetry.orchestratedStartedSkippedPayload",
    "skillbill.application.telemetry.orchestratedPayload",
    "skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeStarted",
    "skillbill.application.telemetry.LifecycleTelemetryService.featureTaskRuntimeFinished",
    "skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckStarted",
    "skillbill.application.telemetry.LifecycleTelemetryService.qualityCheckFinished",
    "skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyStarted",
    "skillbill.application.telemetry.LifecycleTelemetryService.featureVerifyFinished",
    "skillbill.application.telemetry.LifecycleTelemetryService.prDescriptionGenerated",
    "skillbill.application.telemetry.LifecycleTelemetryService.goalStarted",
    "skillbill.application.telemetry.LifecycleTelemetryService.goalSubtaskFinished",
    "skillbill.application.telemetry.LifecycleTelemetryService.goalFinished",
    "skillbill.application.telemetry.LifecycleTelemetryService.goalIssueFinished",
    "skillbill.workflow.engine.WorkflowEngine.continueDecision",
    "skillbill.learnings.learningPayload",
    "skillbill.learnings.learningSummaryPayload",
    "skillbill.learnings.scopeCounts",
    "skillbill.learnings.learningSessionJson",
    "skillbill.learnings.summarizeLearningReferences",
    "skillbill.learnings.learningEntryPayload",
    "skillbill.application.workflow.model.WorkflowUpdateRequest.stepUpdates",
    "skillbill.application.workflow.model.WorkflowUpdateRequest.artifactsPatch",
    "skillbill.application.workflow.model.DecompositionManifestWriteRequest.planningResult",
    "skillbill.application.workflow.model.DecompositionManifestRuntimeUpdate.stepUpdates",
    "skillbill.application.workflow.model.DecompositionManifestRuntimeUpdate.artifactsPatch",
    "skillbill.application.workflow.model.DecompositionManifestRuntimeUpdate.existingArtifacts",
    "skillbill.install.model.buildInstallPlanWireMap",
    "skillbill.scaffold.model.PlatformManifest.customFields",
    "skillbill.telemetry.model.TelemetryConfigDocument.payload",
    "skillbill.telemetry.model.TelemetryProxyCapabilities.additionalFields",
    "skillbill.telemetry.model.TelemetryRemoteStatsResult.metrics",
    "skillbill.workflow.engine.model.WorkflowSnapshotView.artifacts",
    "skillbill.workflow.engine.model.WorkflowContinueView.stepArtifacts",
    "skillbill.workflow.engine.model.WorkflowContinueView.extraFields",
    "skillbill.workflow.engine.model.WorkflowContinueView.sessionSummary",
    "skillbill.workflow.engine.model.WorkflowUpdateInput.stepUpdates",
    "skillbill.workflow.engine.model.WorkflowUpdateInput.artifactsPatch",
    "skillbill.ports.validation.model.RepoValidationReport.toPayload",
    "skillbill.ports.validation.model.ReleaseRefMetadata.toPayload",
    "skillbill.ports.review.model.GovernedReviewEvidenceCodec.TOOL_SPECS",
    "skillbill.ports.review.model.GovernedReviewEvidenceCodec.readRequest",
    "skillbill.ports.review.model.GovernedReviewEvidenceCodec.expansionRequest",
    "skillbill.ports.review.model.GovernedReviewEvidenceCodec.payload",
  )

  val contractsForbiddenImports: List<String> =
    listOf(
      "com.networknt.",
      "com.fasterxml.jackson.",
      "java.nio.file.Files",
    )
  val contractsForbiddenSourceReferences: List<String> =
    listOf(
      "com.networknt.",
      "com.fasterxml.jackson.",
      "java.nio.file.Files",
      "Files.",
    )
  val directFileIoImports: List<String> =
    listOf(
      "java.io.File",
      "java.nio.file.Files",
      "kotlin.io.path",
      "kotlin.io.path.readText",
      "kotlin.io.path.writeText",
      "kotlin.io.path.inputStream",
      "kotlin.io.path.outputStream",
      "kotlin.io.path.bufferedReader",
      "kotlin.io.path.bufferedWriter",
    )
  val directFileIoSourceReferences: List<String> =
    listOf(
      "java.io.File",
      "java.nio.file.Files",
      "Files.",
      ".toFile()",
      ".readText()",
      ".writeText()",
      ".inputStream()",
      ".outputStream()",
      ".bufferedReader()",
      ".bufferedWriter()",
      "kotlin.io.path.readText",
      "kotlin.io.path.writeText",
      "kotlin.io.path.inputStream",
      "kotlin.io.path.outputStream",
      "kotlin.io.path.bufferedReader",
      "kotlin.io.path.bufferedWriter",
    )
  val processAccessSourceReferences: List<String> =
    listOf(
      "System.getenv",
      "System.getProperty",
    )
  val boundaryFrameworkImports: List<String> =
    listOf(
      "com.github.ajalt.clikt",
      "com.zaxxer.hikari",
      "io.ktor.client",
      "java.net.HttpURLConnection",
      "java.net.URL",
      "java.net.URLConnection",
      "java.net.http",
      "java.sql",
      "javax.sql",
      "okhttp3",
      "org.http4k",
      "org.jooq",
      "org.sqlite",
      "retrofit2",
    )
  val boundaryFrameworkSourceReferences: List<String> =
    listOf(
      "com.github.ajalt.clikt",
      "com.zaxxer.hikari",
      "HttpURLConnection",
      "io.ktor.client",
      "java.net.HttpURLConnection",
      "java.net.URL",
      "java.net.URLConnection",
      "java.net.http",
      "java.sql",
      "javax.sql",
      "okhttp3",
      "org.http4k",
      "org.jooq",
      "org.sqlite",
      "retrofit2",
    )
  val homeExpansionSourceReferences: List<String> =
    listOf(
      "== \"~\"",
      ".startsWith(\"~/\")",
      ".removePrefix(\"~/\")",
    )

  val domainEffectPuritySourceReferences: List<String> =
    listOf(
      "UUID.randomUUID",
      "LocalDate.now",
      "Instant.now",
      "System.currentTimeMillis",
      "System.nanoTime",
      "Clock.system",
      "java.util.logging",
    )
  val packagePattern: Regex = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
  val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
  val portFunctionStartPattern: Regex = Regex("^fun\\s+([A-Za-z0-9_]+)\\s*\\(")
  val portFunctionSignaturePattern: Regex =
    Regex("fun\\s+([A-Za-z0-9_]+)\\s*\\((.*?)\\)\\s*:\\s*([A-Za-z0-9_.<>]+)")
  val publicModelDeclarationPattern: Regex =
    Regex(
      "^\\s*(?:public\\s+)?(data\\s+class|enum\\s+class|sealed\\s+(?:class|interface))\\s+([A-Za-z0-9_]+)",
      RegexOption.MULTILINE,
    )
  val scopeDeclarationPattern: Regex =
    Regex(
      """^\s*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+""" +
        """|data\s+|inner\s+|enum\s+|annotation\s+|value\s+|fun\s+)*""" +
        """(?:class|object|interface)\s+([A-Za-z0-9_]+)""",
    )
}
