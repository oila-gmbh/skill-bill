package skillbill.infrastructure.fs.validation

import me.tatarka.inject.annotations.Inject
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@Inject
class FileSystemValidationGateRunner(
  private val clock: Clock,
) : ValidationGateRunner {
  override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
    val started = System.nanoTime()
    val artifactFloor = clock.instant().truncatedTo(ChronoUnit.SECONDS)
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

  companion object {
    private const val GATE_TIMEOUT_MINUTES = 120L
    private const val NANOS_PER_MILLIS = 1_000_000L
    internal const val DEFAULT_EXECUTED_WORK_WHEN_UNDECLARED = 1
    internal const val UNPARSEABLE_GATE_MODULE = "<validation-gate>"
    internal const val UNPARSEABLE_GATE_RULE_ID = "unparseable_gate_failure"
    internal val GRADLE_EXECUTED_PATTERN = Regex("""(\d+)\s+executed""", RegexOption.IGNORE_CASE)
    internal val DOCUMENT_BUILDER = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
      isValidating = false
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder()

    internal fun findingIdentity(finding: ValidationGateFinding): String =
      "${finding.module}|${finding.ruleOrTestId}|${finding.message}|${finding.location}"

    internal fun producedByThisRun(path: Path, artifactFloor: Instant): Boolean =
      runCatching { !Files.getLastModifiedTime(path).toInstant().isBefore(artifactFloor) }.getOrDefault(true)

    internal fun expandGlob(repoRoot: Path, glob: String): List<Path> =
      fileSystemValidationGateExpandGlob(repoRoot, glob)
  }
}

class ValidationGateProcessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
