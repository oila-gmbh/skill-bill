package skillbill.cli.scaffold

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.CliRunState
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.kernel.formatOption
import skillbill.cli.model.CliRunInputs
import skillbill.ports.scaffold.ScaffoldGateway
import skillbill.ports.scaffold.UnsupportedScaffoldGateway
import java.nio.file.Path

@Inject
class ListSkillsCommand(
  private val state: CliRunState,
  private val scaffoldGateway: ScaffoldGateway,
) : DocumentedCliCommand("list", "List governed skills and agent add-ons with their authoring and validation status.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val skillNames by option(
    "--skill-name",
    help = "Optional governed skill or agent-addon:<slug> identity. Repeat to target multiple entries.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldGateway.list(Path.of(repoRoot), skillNames).toCliMap()
      }
  }
}

@Inject
class ShowSkillCommand(
  private val state: CliRunState,
  private val scaffoldGateway: ScaffoldGateway,
) : DocumentedCliCommand(
  "show",
  "Show one governed skill or agent-addon:<slug> with authored source and metadata.",
) {
  private val skillName by argument(help = "Governed skill name to inspect.")
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val content by option("--content", help = "How much content.md text to include.")
    .choice("none", "preview", "full")
    .default("preview")
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldGateway.show(Path.of(repoRoot), skillName, content).toCliMap()
      }
  }
}

@Inject
class ExplainSkillCommand(
  private val state: CliRunState,
  private val scaffoldGateway: ScaffoldGateway,
) : DocumentedCliCommand(
  "explain",
  "Explain the governed authoring boundary and the CLI workflow for content-managed skills.",
) {
  private val skillName by argument(help = "Optional governed skill name to explain with concrete paths.").optional()
  private val repoRoot by option("--repo-root", help = "Repo root to inspect when explaining one skill.").default(".")
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldGateway.explain(Path.of(repoRoot), skillName).toCliMap()
      }
  }
}

@Inject
class ValidateSkillCommand(
  private val state: CliRunState,
  private val scaffoldGateway: ScaffoldGateway,
) : DocumentedCliCommand("validate", "Run the repo validator, or validate specific skills only.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to validate. Defaults to the current working directory.",
  )
    .default(".")
  private val skillNames by option(
    "--skill-name",
    help = "Optional skill name to validate in isolation. Repeat to target multiple skills.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format, successExitCode = { payload -> if (payload["status"] == "pass") 0 else 1 }) {
        scaffoldGateway.validate(Path.of(repoRoot), skillNames).toCliMap()
      }
  }
}

@Inject
class UpgradeSkillsCommand(
  private val state: CliRunState,
  scaffoldGateway: ScaffoldGateway,
) : WrapperRegenerationCommand("upgrade", state, scaffoldGateway)

@Inject
class RenderSkillsCommand(
  private val state: CliRunState,
  private val scaffoldGateway: ScaffoldGateway,
) : DocumentedCliCommand("render", "Render scaffold-managed files to stdout without writing to disk.") {
  private val positionalSkillName by argument(help = "Governed skill name to render.").optional()
  private val optionSkillName by option("--skill-name", help = "Governed skill name to render.")
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val dryRun by option("--dry-run", help = "Accepted no-op alias for read-only render output.")
    .flag(default = false)

  override fun run() {
    val skillName = resolveRenderSkillName(positionalSkillName, optionSkillName)
    completeRenderText(state, Path.of(repoRoot), skillName, dryRun, scaffoldGateway)
  }
}

open class WrapperRegenerationCommand(
  name: String,
  private val state: CliRunState,
  private val scaffoldGateway: ScaffoldGateway,
) : DocumentedCliCommand(name, "Validate governed render output and regenerate native-agent artifacts.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to upgrade. Defaults to the current working directory.",
  )
    .default(".")
  private val skipValidate by option("--skip-validate", help = "Skip validation after wrapper regeneration.")
    .flag(default = false)
  private val skillNames by option(
    "--skill-name",
    help = "Optional governed or horizontal skill name to regenerate. Repeat to target multiple skills.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    state.result =
      authoringResult(format) {
        scaffoldGateway.upgrade(Path.of(repoRoot), skillNames, validate = !skipValidate).toCliMap()
      }
  }
}

@Inject
class EditSkillCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val scaffoldGateway: ScaffoldGateway,
  private val unsupportedScaffoldGateway: UnsupportedScaffoldGateway,
) : DocumentedCliCommand("edit", "Edit a content-managed skill's authored content.md and validate render output.") {
  private val skillName by argument(help = "Governed skill name to edit.")
  private val repoRoot by option("--repo-root", help = "Repo root to edit. Defaults to the current working directory.")
    .default(".")
  private val bodyFile by option("--body-file", help = "Replace content.md from a file path (or '-' for stdin).")
  private val editor by option("--editor", help = "Open content.md in \$VISUAL or \$EDITOR.").flag(default = false)
  private val section by option("--section", help = "Optional authored H2 section name to edit in isolation.")
  private val format by formatOption()

  override fun run() {
    state.result = editSkillResult(
      EditSkillRunArgs(
        inputs = inputs,
        scaffoldGateway = scaffoldGateway,
        unsupportedScaffoldGateway = unsupportedScaffoldGateway,
        skillName = skillName,
        repoRoot = repoRoot,
        bodyFile = bodyFile,
        editor = editor,
        section = section,
        format = format,
      ),
    )
  }
}

@Inject
class FillSkillCommand(
  private val state: CliRunState,
  private val inputs: CliRunInputs,
  private val scaffoldGateway: ScaffoldGateway,
) : DocumentedCliCommand("fill", "Write authored content into content.md and validate render output.") {
  private val skillName by argument(help = "Governed skill name to fill.")
  private val repoRoot by option("--repo-root", help = "Repo root to edit. Defaults to the current working directory.")
    .default(".")
  private val body by option("--body", help = "Body text to write.")
  private val bodyFile by option("--body-file", help = "Read body text from a file path or '-' for stdin.")
  private val section by option("--section", help = "Optional authored H2 section name to replace.")
  private val format by formatOption()

  override fun run() {
    state.result = fillSkillResult(
      FillSkillRunArgs(
        inputs = inputs,
        scaffoldGateway = scaffoldGateway,
        skillName = skillName,
        repoRoot = repoRoot,
        body = body,
        bodyFile = bodyFile,
        section = section,
        format = format,
      ),
    )
  }
}
