package skillbill.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.application.SystemService

@Inject
class VersionCommand(
  private val service: SystemService,
  private val state: CliRunState,
) : DocumentedCliCommand("version", "Show the installed skill-bill version.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.version(), format)
  }
}

@Inject
class DoctorCliCommand(
  private val service: SystemService,
  private val state: CliRunState,
) : DocumentedCliCommand("doctor", "Check skill-bill installation health.") {
  private val subject by argument(help = "Optional diagnostic subject. Use `skill` for one governed skill.")
    .optional()
  private val skillName by argument(help = "Governed skill name when diagnosing one skill.").optional()
  private val repoRoot by option("--repo-root", help = "Repo root to inspect when using `doctor skill`.").default(".")
  private val content by option("--content", help = "How much content.md text to include when using `doctor skill`.")
    .choice("none", "preview", "full")
    .default("preview")
  private val format by formatOption()

  override fun run() {
    if (subject == null) {
      state.complete(service.doctor(state.dbOverride), format)
    } else {
      state.result = retiredSubjectResult(subject.orEmpty(), skillName.orEmpty(), repoRoot, content)
    }
  }
}

private fun retiredSubjectResult(
  subject: String,
  skillName: String,
  repoRoot: String,
  content: String,
): CliExecutionResult {
  val replacementSkillName = skillName.ifBlank { "<skill-name>" }
  val replacement = when (subject) {
    "skill" -> "skill-bill show $replacementSkillName --repo-root $repoRoot --content $content"
    else -> "skill-bill doctor"
  }
  val message = when (subject) {
    "skill" -> "doctor skill was retired in SKILL-32; use `$replacement` instead."
    else -> "doctor subject '$subject' is unsupported; use `$replacement` instead."
  }
  return CliExecutionResult(exitCode = 1, stdout = message)
}
