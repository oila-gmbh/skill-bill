package skillbill.infrastructure.fs.validation

import me.tatarka.inject.annotations.Inject
import org.w3c.dom.Element
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunOutcome
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateFindingsFormat
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@Inject
class FileSystemValidationGateRunner : ValidationGateRunner {
  override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
    val started = System.nanoTime()
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
      val parsedFindings = parseFindings(request, stdout)
      val outcome = deriveOutcome(exitCode, parsedFindings, executedWorkUnits, request.terminalVerifying)
      ValidationGateRunResult(
        exitCode = exitCode,
        durationMs = durationMs,
        outcome = outcome,
        cacheMode = request.cacheMode,
        executedWorkUnits = executedWorkUnits,
        findings = finalizeFindings(request, parsedFindings, exitCode, outcome),
      )
    } finally {
      runCatching { Files.deleteIfExists(outputFile) }
    }
  }

  private fun deriveOutcome(
    exitCode: Int,
    findings: List<ValidationGateFinding>,
    executedWorkUnits: Int,
    terminalVerifying: Boolean,
  ): ValidationGateRunOutcome = when {
    terminalVerifying && executedWorkUnits == 0 -> ValidationGateRunOutcome.REJECTED_ZERO_WORK
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

  private fun parseFindings(request: ValidationGateRunRequest, stdout: String): List<ValidationGateFinding> {
    val artifacts = parseArtifactFindings(request)
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
          message = "Validation gate reported outcome=${outcome.wireValue} exit=$exitCode " +
            "without parseable findings; repair the underlying failure the gate detected.",
          location = null,
        ),
      )
    }
    return parsed
  }

  private fun parseArtifactFindings(request: ValidationGateRunRequest): List<ValidationGateFinding> =
    when (request.declaration.findings.format) {
      ValidationGateFindingsFormat.JUNIT_XML ->
        request.declaration.findings.artifactGlobs
          .flatMap { glob -> expandGlob(request.repoRoot, glob) }
          .flatMap(::parseJUnitXmlFile)
          .distinctBy { findingIdentity(it) }
    }

  private fun parseCompilerDiagnostics(
    request: ValidationGateRunRequest,
    stdout: String,
  ): List<ValidationGateFinding> =
    when (request.declaration.findings.compilerDiagnostics.format) {
      ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT ->
        parseGradleKotlinCompilerStdout(request.repoRoot, stdout)
    }

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
    private val GRADLE_EXECUTED_PATTERN = Regex("""(\d+)\s+executed""", RegexOption.IGNORE_CASE)
    private val COMPILER_E_LINE = Regex("""^e:\s+(.*)$""")
    private val COMPILER_LOCATION = Regex("""^(?:file://)?(.+):(\d+):(\d+)\s+(.*)$""")
    private val GRADLE_TASK_PREFIX = Regex("""^>\s*Task\s+:\S+\s+""")
    private val DOCUMENT_BUILDER = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
      isValidating = false
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder()

    private fun findingIdentity(finding: ValidationGateFinding): String =
      "${finding.module}|${finding.ruleOrTestId}|${finding.message}|${finding.location}"

    internal fun parseGradleKotlinCompilerStdout(repoRoot: Path, stdout: String): List<ValidationGateFinding> {
      val repo = repoRoot.toAbsolutePath().normalize()
      val repoUri = repo.toUri().toString().removeSuffix("/")
      val repoPath = repo.toString()
      return stdout.lineSequence().mapNotNull { line ->
        val eMatch = COMPILER_E_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
        val rest = eMatch.groupValues[1].trim()
        val locationMatch = COMPILER_LOCATION.matchEntire(rest) ?: return@mapNotNull null
        val rawPath = locationMatch.groupValues[1].removePrefix("file://")
        val lineNo = locationMatch.groupValues[2]
        val column = locationMatch.groupValues[3]
        val message = locationMatch.groupValues[4].trim()
          .replace(GRADLE_TASK_PREFIX, "")
          .replace(repoUri, "")
          .replace("file://$repoPath", "")
          .replace(repoPath, "")
          .replace("file://", "")
          .trim()
        val absolute = Path.of(rawPath).toAbsolutePath().normalize()
        val relative = if (absolute.startsWith(repo)) {
          repo.relativize(absolute).toString().replace('\\', '/')
        } else {
          rawPath.replace('\\', '/').removePrefix("/")
        }
        val module = relative.substringBefore('/').ifBlank { "<compiler>" }
        ValidationGateFinding(
          module = module,
          ruleOrTestId = "kotlin_compiler",
          message = message,
          location = "$relative:$lineNo:$column",
        )
      }.toList()
    }

    internal fun expandGlob(repoRoot: Path, glob: String): List<Path> {
      val normalized = glob.replace('\\', '/')
      val matcher = FileSystems.getDefault().getPathMatcher("glob:$normalized")
      if (!Files.isDirectory(repoRoot)) return emptyList()
      return Files.walk(repoRoot).use { stream ->
        stream
          .filter { Files.isRegularFile(it) }
          .filter { candidate ->
            val relative = repoRoot.relativize(candidate).toString().replace('\\', '/')
            matcher.matches(Path.of(relative))
          }
          .toList()
      }
    }
  }
}

class ValidationGateProcessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
