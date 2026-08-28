package skillbill.infrastructure.fs.validation

import me.tatarka.inject.annotations.Inject
import org.w3c.dom.Element
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.validation.model.unparseableGateFailureMessage
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateFindingsFormat
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@Inject
class FileSystemValidationGateRunner : ValidationGateRunner {
  override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
    val started = System.nanoTime()
    val artifactFloor = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    val outputFile = Files.createTempFile("skillbill-validation-gate", ".out")
    return try {
      val process = ProcessBuilder(request.argv)
        .directory(request.repoRoot.toFile())
        .redirectErrorStream(true)
        .redirectOutput(outputFile.toFile())
        .start()
      val finished = process.waitFor(GATE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
      if (!finished) {
        process.destroyForcibly()
        throw ValidationGateProcessException(
          "Validation gate command timed out after ${GATE_TIMEOUT_MINUTES}m: ${request.argv.joinToString(" ")}",
        )
      }
      val stdout = Files.readString(outputFile)
      val durationMs = ((System.nanoTime() - started) / NANOS_PER_MILLIS).coerceAtLeast(0L)
      val executedWorkUnits = deriveExecutedWorkUnits(request, stdout)
      val exitCode = process.exitValue()
      val parsedFindings = parseFindings(request, stdout, artifactFloor)
      val outcome = deriveOutcome(exitCode, parsedFindings)
      ValidationGateRunResult(
        exitCode = exitCode,
        durationMs = durationMs,
        outcome = outcome,
        cacheMode = request.cacheMode,
        executedWorkUnits = executedWorkUnits,
        findings = finalizeFindings(request, parsedFindings, exitCode, outcome, stdout),
        stdout = stdout,
      )
    } finally {
      runCatching { Files.deleteIfExists(outputFile) }
    }
  }

  private fun deriveOutcome(exitCode: Int, findings: List<ValidationGateFinding>): ValidationGateRunOutcome = when {
    findings.isNotEmpty() || exitCode != 0 -> ValidationGateRunOutcome.FAILED
    else -> ValidationGateRunOutcome.PASSED
  }

  private fun deriveExecutedWorkUnits(request: ValidationGateRunRequest, stdout: String): Int {
    val signal = request.declaration.findings.executedWork ?: return DEFAULT_EXECUTED_WORK_WHEN_UNDECLARED
    return when (signal.format) {
      ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY ->
        GRADLE_EXECUTED_PATTERN.find(stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
  }

  private fun parseFindings(
    request: ValidationGateRunRequest,
    stdout: String,
    artifactFloor: Instant,
  ): List<ValidationGateFinding> {
    val artifacts = parseArtifactFindings(request, artifactFloor)
    if (request.findingParseMode != ValidationGateFindingParseMode.COLLECT_ALL) {
      return artifacts
    }
    val compiler = parseCompilerDiagnostics(request, stdout)
    return (compiler + artifacts).distinctBy { findingIdentity(it) }
  }

  private fun finalizeFindings(
    request: ValidationGateRunRequest,
    parsed: List<ValidationGateFinding>,
    exitCode: Int,
    outcome: ValidationGateRunOutcome,
    stdout: String,
  ): List<ValidationGateFinding> {
    if (request.findingParseMode != ValidationGateFindingParseMode.COLLECT_ALL) {
      return parsed
    }
    val failed = exitCode != 0 || outcome == ValidationGateRunOutcome.FAILED
    if (failed && parsed.isEmpty()) {
      return listOf(
        ValidationGateFinding(
          module = UNPARSEABLE_GATE_MODULE,
          ruleOrTestId = UNPARSEABLE_GATE_RULE_ID,
          message = unparseableGateFailureMessage(
            gateLabel = "Validation gate",
            outcome = outcome.wireValue,
            exitCode = exitCode,
            stdout = stdout,
          ),
          location = null,
        ),
      )
    }
    return parsed
  }

  private fun parseArtifactFindings(
    request: ValidationGateRunRequest,
    artifactFloor: Instant,
  ): List<ValidationGateFinding> = when (request.declaration.findings.format) {
    ValidationGateFindingsFormat.JUNIT_XML ->
      request.declaration.findings.artifactGlobs
        .flatMap { glob -> expandGlob(request.repoRoot, glob) }
        .filter { producedByThisRun(it, artifactFloor) }
        .flatMap { path -> parseArtifactFile(request.repoRoot, path) }
        .distinctBy { findingIdentity(it) }
  }

  private fun parseArtifactFile(repoRoot: Path, path: Path): List<ValidationGateFinding> =
    if (path.toString().replace('\\', '/').contains("/reports/detekt/")) {
      parseDetektXmlFile(repoRoot, path)
    } else {
      parseJUnitXmlFile(path)
    }

  private fun parseCompilerDiagnostics(
    request: ValidationGateRunRequest,
    stdout: String,
  ): List<ValidationGateFinding> = when (request.declaration.findings.compilerDiagnostics.format) {
    ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT ->
      parseGradleKotlinCompilerStdout(request.repoRoot, stdout) +
        parseGradleQualityToolStdout(request.repoRoot, stdout)
  }

  private fun parseDetektXmlFile(repoRoot: Path, path: Path): List<ValidationGateFinding> = runCatching {
    val document = DOCUMENT_BUILDER.parse(path.toFile())
    val repo = repoRoot.toAbsolutePath().normalize()
    val files = document.getElementsByTagName("file")
    buildList {
      for (fileIndex in 0 until files.length) {
        val fileElement = files.item(fileIndex) as Element
        val rawFileName = fileElement.getAttribute("name").trim()
        if (rawFileName.isEmpty()) continue
        val relativeFile = repoRelativeQualityPath(repo, rawFileName)
        val module = relativeFile.substringBefore('/').ifBlank { "<detekt>" }
        val errors = fileElement.getElementsByTagName("error")
        for (errorIndex in 0 until errors.length) {
          val error = errors.item(errorIndex) as Element
          val line = error.getAttribute("line").trim()
          val rule = error.getAttribute("source").substringAfterLast('.').ifBlank { "detekt" }
          val message = error.getAttribute("message").ifBlank { error.textContent?.trim().orEmpty() }
          add(
            ValidationGateFinding(
              module = module,
              ruleOrTestId = rule,
              message = message,
              location = listOf(relativeFile, line).filter(String::isNotBlank).joinToString(":").ifBlank { null },
            ),
          )
        }
      }
    }
  }.getOrDefault(emptyList())

  private fun parseJUnitXmlFile(path: Path): List<ValidationGateFinding> = runCatching {
    val document = DOCUMENT_BUILDER.parse(path.toFile())
    val testcases = document.getElementsByTagName("testcase")
    buildList {
      for (index in 0 until testcases.length) {
        val testcase = testcases.item(index) as Element
        val failure = testcase.getElementsByTagName("failure").item(0) as? Element
          ?: testcase.getElementsByTagName("error").item(0) as? Element
          ?: continue
        val classname = testcase.getAttribute("classname").ifBlank { path.parent?.fileName?.toString().orEmpty() }
        val name = testcase.getAttribute("name").ifBlank { "unknown" }
        add(
          ValidationGateFinding(
            module = classname.substringBeforeLast('.').ifBlank { classname },
            ruleOrTestId = name,
            message = failure.getAttribute("message").ifBlank { failure.textContent?.trim().orEmpty() },
            location = listOfNotNull(
              testcase.getAttribute("file").takeIf(String::isNotBlank),
              testcase.getAttribute("line").takeIf(String::isNotBlank),
            ).joinToString(":").ifBlank { null },
          ),
        )
      }
    }
  }.getOrDefault(emptyList())

  companion object {
    private const val GATE_TIMEOUT_MINUTES = 120L
    private const val NANOS_PER_MILLIS = 1_000_000L
    private const val DEFAULT_EXECUTED_WORK_WHEN_UNDECLARED = 1
    private const val UNPARSEABLE_GATE_MODULE = "<validation-gate>"
    private const val UNPARSEABLE_GATE_RULE_ID = "unparseable_gate_failure"
    private const val COMPILER_LOCATION_PATH_GROUP = 1
    private const val COMPILER_LOCATION_LINE_GROUP = 2
    private const val COMPILER_LOCATION_COLUMN_GROUP = 3
    private const val COMPILER_LOCATION_MESSAGE_GROUP = 4
    private const val QUALITY_TOOL_PATH_GROUP = 1
    private const val QUALITY_TOOL_LINE_GROUP = 2
    private const val QUALITY_TOOL_COLUMN_GROUP = 3
    private const val QUALITY_TOOL_MESSAGE_GROUP = 4
    private const val QUALITY_TOOL_RULE_GROUP = 5
    private val GRADLE_EXECUTED_PATTERN = Regex("""(\d+)\s+executed""", RegexOption.IGNORE_CASE)
    private val COMPILER_E_LINE = Regex("""^e:\s+(.*)$""")
    private val COMPILER_LOCATION = Regex("""^(?:file://)?(.+):(\d+):(\d+)\s+(.*)$""")
    private val QUALITY_TOOL_LINE =
      Regex("""^(?:file://)?(.+\.kt):(\d+):(\d+):\s+(.+?)\s+\[([A-Za-z0-9]+)]\s*$""")
    private val GRADLE_TASK_PREFIX = Regex("""^>\s*Task\s+:\S+\s+""")
    private val DOCUMENT_BUILDER = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
      isValidating = false
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder()

    private fun findingIdentity(finding: ValidationGateFinding): String =
      "${finding.module}|${finding.ruleOrTestId}|${finding.message}|${finding.location}"

    internal fun parseGradleQualityToolStdout(repoRoot: Path, stdout: String): List<ValidationGateFinding> {
      val repo = repoRoot.toAbsolutePath().normalize()
      return stdout.lineSequence().mapNotNull { line ->
        val match = QUALITY_TOOL_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
        val rawPath = match.groupValues[QUALITY_TOOL_PATH_GROUP].removePrefix("file://")
        val lineNo = match.groupValues[QUALITY_TOOL_LINE_GROUP]
        val column = match.groupValues[QUALITY_TOOL_COLUMN_GROUP]
        val message = match.groupValues[QUALITY_TOOL_MESSAGE_GROUP].trim()
        val rule = match.groupValues[QUALITY_TOOL_RULE_GROUP].trim()
        val relative = repoRelativeQualityPath(repo, rawPath)
        val module = relative.substringBefore('/').ifBlank { "<quality>" }
        ValidationGateFinding(
          module = module,
          ruleOrTestId = rule,
          message = message,
          location = "$relative:$lineNo:$column",
        )
      }.toList()
    }

    internal fun parseGradleKotlinCompilerStdout(repoRoot: Path, stdout: String): List<ValidationGateFinding> {
      val repo = repoRoot.toAbsolutePath().normalize()
      val canonicalRepo = canonicalizeExisting(repo)
      val repoPrefixes = listOf(
        canonicalRepo.toUri().toString().removeSuffix("/"),
        repo.toUri().toString().removeSuffix("/"),
        "file://$canonicalRepo",
        "file://$repo",
        canonicalRepo.toString(),
        repo.toString(),
        "file://",
      ).distinct()
      return stdout.lineSequence().mapNotNull { line ->
        val eMatch = COMPILER_E_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
        val rest = eMatch.groupValues[1].trim()
        val locationMatch = COMPILER_LOCATION.matchEntire(rest) ?: return@mapNotNull null
        val rawPath = locationMatch.groupValues[COMPILER_LOCATION_PATH_GROUP].removePrefix("file://")
        val lineNo = locationMatch.groupValues[COMPILER_LOCATION_LINE_GROUP]
        val column = locationMatch.groupValues[COMPILER_LOCATION_COLUMN_GROUP]
        var message = locationMatch.groupValues[COMPILER_LOCATION_MESSAGE_GROUP].trim()
          .replace(GRADLE_TASK_PREFIX, "")
        for (prefix in repoPrefixes) {
          message = message.replace(prefix, "")
        }
        message = message.trim()
        val relative = repoRelativeCompilerPath(repo, canonicalRepo, Path.of(rawPath), rawPath)
        val module = relative.substringBefore('/').ifBlank { "<compiler>" }
        ValidationGateFinding(
          module = module,
          ruleOrTestId = "kotlin_compiler",
          message = message,
          location = "$relative:$lineNo:$column",
        )
      }.toList()
    }

    private fun repoRelativeQualityPath(repo: Path, rawPath: String): String {
      val diagnosticPath = Path.of(rawPath.removePrefix("file://"))
      val canonicalRepo = canonicalizeExisting(repo)
      return repoRelativeCompilerPath(repo, canonicalRepo, diagnosticPath, rawPath)
    }

    private fun repoRelativeCompilerPath(
      repo: Path,
      canonicalRepo: Path,
      diagnosticPath: Path,
      rawPath: String,
    ): String {
      val absolute = diagnosticPath.toAbsolutePath().normalize()
      val canonicalFile = canonicalizeMaybeMissing(absolute)
      if (canonicalFile.startsWith(canonicalRepo)) {
        return canonicalRepo.relativize(canonicalFile).toString().replace('\\', '/')
      }
      if (absolute.startsWith(repo)) {
        return repo.relativize(absolute).toString().replace('\\', '/')
      }
      return rawPath.replace('\\', '/').removePrefix("/")
    }

    private fun canonicalizeExisting(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path)

    private fun canonicalizeMaybeMissing(path: Path): Path {
      if (Files.exists(path)) {
        return canonicalizeExisting(path)
      }
      val tail = ArrayDeque<Path>()
      var current: Path? = path
      while (current != null && !Files.exists(current)) {
        current.fileName?.let(tail::addFirst)
        current = current.parent
      }
      val realBase = current?.let(::canonicalizeExisting) ?: return path
      return tail.fold(realBase) { acc, name -> acc.resolve(name) }
    }

    internal fun producedByThisRun(path: Path, artifactFloor: Instant): Boolean =
      runCatching { !Files.getLastModifiedTime(path).toInstant().isBefore(artifactFloor) }.getOrDefault(true)

    internal fun expandGlob(repoRoot: Path, glob: String): List<Path> {
      val normalized = glob.replace('\\', '/')
      val matcher = FileSystems.getDefault().getPathMatcher("glob:$normalized")
      if (!Files.isDirectory(repoRoot)) return emptyList()
      val matches = ArrayList<Path>()
      Files.walkFileTree(
        repoRoot,
        object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (dir != repoRoot && dir.fileName?.toString() == ".git") {
              return FileVisitResult.SKIP_SUBTREE
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val relative = repoRoot.relativize(file).toString().replace('\\', '/')
            if (matcher.matches(Path.of(relative))) {
              matches.add(file)
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            if (exc is NoSuchFileException) {
              return FileVisitResult.CONTINUE
            }
            throw exc
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc is NoSuchFileException) {
              return FileVisitResult.CONTINUE
            }
            if (exc != null) {
              throw exc
            }
            return FileVisitResult.CONTINUE
          }
        },
      )
      return matches
    }
  }
}

class ValidationGateProcessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
