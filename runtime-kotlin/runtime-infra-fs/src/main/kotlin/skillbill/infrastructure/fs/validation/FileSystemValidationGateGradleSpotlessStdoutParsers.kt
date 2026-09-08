package skillbill.infrastructure.fs.validation

import skillbill.ports.validation.model.ValidationGateFinding
import java.nio.file.Path

internal object FileSystemValidationGateGradleSpotlessStdoutParsers {
  private const val MAX_MESSAGE_PARTS = 3
  private val STEP_PROBLEM =
    Regex("""^Step '([^']+)' found problem in '([^']+)':\s*$""")
  private val ERROR_LINE =
    Regex("""^Error on line:\s*(\d+),\s*column:\s*(\d+)\s*$""")
  private val ASSERTION_ERROR =
    Regex("""^(?:java\.lang\.)?AssertionError:\s*Error on line:\s*(\d+),\s*column:\s*(\d+)\s*$""")
  private val TASK_FAILURE_HEADER =
    Regex("""Execution failed for task '?([^']+)'?\.""")
  private val TASK_RULE_IDS = setOf("spotlessCheck", "spotlessKotlinCheck")

  fun parseGradleSpotlessStdout(repoRoot: Path, stdout: String): List<ValidationGateFinding> {
    val repo = repoRoot.toAbsolutePath().normalize()
    val lines = stdout.lineSequence().map { it.trimEnd() }.toList()
    val findings = mutableListOf<ValidationGateFinding>()
    var index = 0
    while (index < lines.size) {
      val step = matchStep(lines[index].trim(), repo)
      if (step == null) {
        index++
      } else {
        val body = readBody(lines, index + 1, step.relative)
        findings += step.toFinding(body)
        index = body.nextIndex
      }
    }
    return findings
  }

  fun enrichSpotlessTaskHeaderFindings(
    findings: List<ValidationGateFinding>,
    stdout: String,
  ): List<ValidationGateFinding> = findings.map { finding ->
    if (finding.location != null || finding.ruleOrTestId !in TASK_RULE_IDS) {
      finding
    } else {
      val excerpt = FileSystemValidationGateGradleSpotlessExcerptSupport.excerpt(stdout)
        ?: return@map finding
      finding.copy(message = "${finding.message} | spotless detail: $excerpt")
    }
  }

  private data class Step(
    val stepName: String,
    val rawPath: String,
    val relative: String,
    val module: String,
  )

  private data class Body(
    val location: String?,
    val messageParts: List<String>,
    val nextIndex: Int,
  )

  private sealed interface Action {
    data object Stop : Action
    data object Skip : Action
    data class SetLocation(val location: String) : Action
    data class AppendMessage(val text: String) : Action
  }

  private fun matchStep(line: String, repo: Path): Step? {
    val match = STEP_PROBLEM.matchEntire(line) ?: return null
    val stepName = match.groupValues[1]
    val rawPath = match.groupValues[2].replace('\\', '/')
    val relative = FileSystemValidationGateGradlePathSupport.repoRelativeQualityPath(repo, rawPath)
    return Step(
      stepName = stepName,
      rawPath = rawPath,
      relative = relative,
      module = moduleFromRelative(relative),
    )
  }

  private fun Step.toFinding(body: Body): ValidationGateFinding = ValidationGateFinding(
    module = module,
    ruleOrTestId = "spotless",
    message = body.messageParts.joinToString(" ").ifBlank {
      "Step '$stepName' found problem in '$rawPath'"
    },
    location = body.location ?: relative,
  )

  private fun readBody(lines: List<String>, startIndex: Int, relative: String): Body {
    var location: String? = null
    val messageParts = mutableListOf<String>()
    var index = startIndex
    var done = false
    while (index < lines.size && !done) {
      when (val action = classifyLine(lines[index].trim(), relative, location)) {
        Action.Stop -> done = true
        Action.Skip -> index++
        is Action.SetLocation -> {
          location = action.location
          index++
        }
        is Action.AppendMessage -> {
          messageParts += action.text
          index++
          done = messageParts.size >= MAX_MESSAGE_PARTS
        }
      }
    }
    return Body(location = location, messageParts = messageParts, nextIndex = index)
  }

  private fun classifyLine(line: String, relative: String, location: String?): Action {
    val errorLocation = ERROR_LINE.matchEntire(line)?.let { match ->
      "$relative:${match.groupValues[1]}:${match.groupValues[2]}"
    }
    val assertionLocation = ASSERTION_ERROR.matchEntire(line)?.let { match ->
      "$relative:${match.groupValues[1]}:${match.groupValues[2]}"
    }
    return when {
      line.isEmpty() -> Action.Skip
      STEP_PROBLEM.matches(line) || TASK_FAILURE_HEADER.matches(line) -> Action.Stop
      errorLocation != null -> Action.SetLocation(errorLocation)
      assertionLocation != null -> Action.SetLocation(location ?: assertionLocation)
      else -> classifyMessage(line)
    }
  }

  private fun classifyMessage(line: String): Action = when {
    line.startsWith("rule:") -> Action.AppendMessage(line)
    line.startsWith("at ") || line.startsWith(">") || line.contains("diffplug.spotless") -> Action.Stop
    line.startsWith("java.lang.") || line.startsWith("Caused by:") -> Action.Skip
    else -> Action.AppendMessage(line)
  }

  private fun moduleFromRelative(relative: String): String {
    val normalized = relative.replace('\\', '/')
    if (normalized.startsWith("runtime-kotlin/")) {
      return normalized.removePrefix("runtime-kotlin/").substringBefore('/')
    }
    return normalized.substringBefore('/').ifBlank { "<spotless>" }
  }
}
