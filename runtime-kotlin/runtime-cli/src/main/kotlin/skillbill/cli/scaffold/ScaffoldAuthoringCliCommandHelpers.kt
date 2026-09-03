package skillbill.cli.scaffold

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.scaffold.ScaffoldService
import skillbill.application.scaffold.UnsupportedScaffoldService
import skillbill.cli.core.CliRunInputs
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import java.nio.file.Path

internal data class EditSkillRunArgs(
  val inputs: CliRunInputs,
  val scaffoldService: ScaffoldService,
  val unsupportedScaffoldService: UnsupportedScaffoldService,
  val skillName: String,
  val repoRoot: String,
  val bodyFile: String?,
  val editor: Boolean,
  val section: String?,
  val format: CliFormat,
)

internal data class FillSkillRunArgs(
  val inputs: CliRunInputs,
  val scaffoldService: ScaffoldService,
  val skillName: String,
  val repoRoot: String,
  val body: String?,
  val bodyFile: String?,
  val section: String?,
  val format: CliFormat,
)

internal fun resolveRenderSkillName(positionalSkillName: String?, optionSkillName: String?): String = when {
  positionalSkillName != null && optionSkillName != null ->
    throw UsageError("Provide the skill name either as an argument or with --skill-name, not both.")
  positionalSkillName != null -> requireNotNull(positionalSkillName)
  optionSkillName != null -> requireNotNull(optionSkillName)
  else -> throw UsageError("Provide a skill name as an argument or with --skill-name.")
}

internal fun editSkillResult(args: EditSkillRunArgs): CliExecutionResult = when {
  args.editor ->
    unsupportedNativeScaffoldResult(
      args.unsupportedScaffoldService.retiredUnsupportedMessage(
        "edit --editor",
        "skill-bill fill ${args.skillName} --body-file <file>",
        editor = true,
      ),
      args.format,
    )
  args.bodyFile != null ->
    authoringResult(args.format) {
      args.scaffoldService.editWithBodyFile(
        Path.of(args.repoRoot),
        args.skillName,
        readCliTextFile(args.bodyFile, args.inputs),
        args.section,
      ).toCliMap()
    }
  else ->
    unsupportedNativeScaffoldResult(
      args.unsupportedScaffoldService.retiredUnsupportedMessage(
        "edit",
        "skill-bill fill ${args.skillName} --body-file <file>",
        editor = false,
      ),
      args.format,
    )
}

internal fun fillSkillResult(args: FillSkillRunArgs): CliExecutionResult = when {
  args.body != null && args.bodyFile != null ->
    errorResult("--body and --body-file are mutually exclusive.", args.format)
  args.body == null && args.bodyFile == null -> errorResult("Either --body or --body-file is required.", args.format)
  else ->
    authoringResult(args.format) {
      args.scaffoldService.fill(
        Path.of(args.repoRoot),
        args.skillName,
        args.body ?: readCliTextFile(args.bodyFile.orEmpty(), args.inputs),
        args.section,
      ).toCliMap()
    }
}
