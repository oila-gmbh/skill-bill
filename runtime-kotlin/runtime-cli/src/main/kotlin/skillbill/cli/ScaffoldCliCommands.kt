@file:Suppress("MaxLineLength", "TooManyFunctions", "LongParameterList", "ReturnCount", "ThrowsCount")

package skillbill.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.install.InstallOperations
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

@Inject
class ScaffoldTopLevelCommands(
  listSkillsCommand: ListSkillsCommand,
  showSkillCommand: ShowSkillCommand,
  explainSkillCommand: ExplainSkillCommand,
  validateSkillCommand: ValidateSkillCommand,
  upgradeSkillsCommand: UpgradeSkillsCommand,
  renderSkillsCommand: RenderSkillsCommand,
  editSkillCommand: EditSkillCommand,
  fillSkillCommand: FillSkillCommand,
  newSkillCommand: NewSkillCommand,
  newCommand: NewCommand,
  createAndFillCommand: CreateAndFillCommand,
  newAddonCommand: NewAddonCommand,
  installCommands: InstallTopLevelCommands,
) {
  val newSkill = newSkillCommand
  val newAlias = newCommand
  val createAndFill = createAndFillCommand
  val newAddon = newAddonCommand
  val install = installCommands
  val commands: List<CliktCommand> =
    listOf(
      listSkillsCommand,
      showSkillCommand,
      explainSkillCommand,
      validateSkillCommand,
      upgradeSkillsCommand,
      renderSkillsCommand,
      editSkillCommand,
      fillSkillCommand,
      newSkill,
      newAlias,
      createAndFill,
      newAddon,
      install.command,
    )
}

@Inject
class InstallTopLevelCommands(
  agentPathCommand: InstallAgentPathCommand,
  detectAgentsCommand: InstallDetectAgentsCommand,
  linkSkillCommand: InstallLinkSkillCommand,
) {
  val command: DocumentedNoOpCliCommand =
    object : DocumentedNoOpCliCommand(
      "install",
      "Install-side primitives (agent-path lookup, agent detection, skill symlinking).",
    ) {}
      .subcommands(
        agentPathCommand,
        detectAgentsCommand,
        linkSkillCommand,
      )
}

@Inject
class ListSkillsCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("list", "List content-managed skills and their authoring status.") {
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to inspect. Defaults to the current working directory.",
  )
    .default(".")
  private val skillNames by option(
    "--skill-name",
    help = "Optional content-managed skill name to include. Repeat to target multiple skills.",
  ).multiple()
  private val format by formatOption()

  override fun run() {
    state.result =
      runPythonCli(
        buildList {
          add("list")
          addAll(listOf("--repo-root", repoRoot))
          skillNames.forEach { skillName -> addAll(listOf("--skill-name", skillName)) }
          addAll(listOf("--format", format.wireName))
        },
        state,
      )
  }
}

@Inject
class ShowSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand(
  "show",
  "Show one content-managed skill with section status, drift, and recommended next commands.",
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
      runPythonCli(
        listOf(
          "show",
          skillName,
          "--repo-root",
          repoRoot,
          "--content",
          content,
          "--format",
          format.wireName,
        ),
        state,
      )
  }
}

@Inject
class ExplainSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand(
  "explain",
  "Explain the governed authoring boundary and the CLI workflow for content-managed skills.",
) {
  private val skillName by argument(help = "Optional governed skill name to explain with concrete paths.").optional()
  private val repoRoot by option("--repo-root", help = "Repo root to inspect when explaining one skill.").default(".")
  private val format by formatOption()

  override fun run() {
    state.result =
      runPythonCli(
        buildList {
          add("explain")
          skillName?.let { add(it) }
          addAll(listOf("--repo-root", repoRoot))
          addAll(listOf("--format", format.wireName))
        },
        state,
      )
  }
}

@Inject
class ValidateSkillCommand(
  private val state: CliRunState,
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
      runPythonCli(
        buildList {
          add("validate")
          addAll(listOf("--repo-root", repoRoot))
          skillNames.forEach { skillName -> addAll(listOf("--skill-name", skillName)) }
          addAll(listOf("--format", format.wireName))
        },
        state,
      )
  }
}

@Inject
class UpgradeSkillsCommand(
  private val state: CliRunState,
) : WrapperRegenerationCommand("upgrade", state)

@Inject
class RenderSkillsCommand(
  private val state: CliRunState,
) : WrapperRegenerationCommand("render", state)

open class WrapperRegenerationCommand(
  name: String,
  private val state: CliRunState,
  private val pythonCommandName: String = name,
) : DocumentedCliCommand(name, "Regenerate scaffold-managed SKILL.md wrappers.") {
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
      runPythonCli(
        buildList {
          add(pythonCommandName)
          addAll(listOf("--repo-root", repoRoot))
          if (skipValidate) add("--skip-validate")
          skillNames.forEach { skillName -> addAll(listOf("--skill-name", skillName)) }
          addAll(listOf("--format", format.wireName))
        },
        state,
      )
  }
}

@Inject
class EditSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("edit", "Edit a content-managed skill's authored content.md and regenerate the wrapper.") {
  private val skillName by argument(help = "Governed skill name to edit.")
  private val repoRoot by option("--repo-root", help = "Repo root to edit. Defaults to the current working directory.")
    .default(".")
  private val bodyFile by option("--body-file", help = "Replace content.md from a file path (or '-' for stdin).")
  private val editor by option("--editor", help = "Open content.md in \$VISUAL or \$EDITOR.").flag(default = false)
  private val section by option("--section", help = "Optional authored H2 section name to edit in isolation.")
  private val format by formatOption()

  override fun run() {
    state.result =
      runPythonCli(
        buildList {
          add("edit")
          add(skillName)
          addAll(listOf("--repo-root", repoRoot))
          bodyFile?.let { addAll(listOf("--body-file", it)) }
          if (editor) add("--editor")
          section?.let { addAll(listOf("--section", it)) }
          addAll(listOf("--format", format.wireName))
        },
        state,
        stdinText = if (bodyFile == "-") state.stdinText else null,
      )
  }
}

@Inject
class FillSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("fill", "Write authored content into content.md, regenerate the wrapper, and validate it.") {
  private val skillName by argument(help = "Governed skill name to fill.")
  private val repoRoot by option("--repo-root", help = "Repo root to edit. Defaults to the current working directory.")
    .default(".")
  private val body by option("--body", help = "Body text to write.")
  private val bodyFile by option("--body-file", help = "Read body text from a file path or '-' for stdin.")
  private val section by option("--section", help = "Optional authored H2 section name to replace.")
  private val format by formatOption()

  override fun run() {
    state.result =
      runPythonCli(
        buildList {
          add("fill")
          add(skillName)
          addAll(listOf("--repo-root", repoRoot))
          body?.let { addAll(listOf("--body", it)) }
          bodyFile?.let { addAll(listOf("--body-file", it)) }
          section?.let { addAll(listOf("--section", it)) }
          addAll(listOf("--format", format.wireName))
        },
        state,
        stdinText = if (bodyFile == "-") state.stdinText else null,
      )
  }
}

@Inject
class NewSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("new-skill", "Scaffold a new skill from a payload file or interactive prompts.") {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option("--interactive", help = "Collect a skill scaffold payload via interactive prompts.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (interactive) {
        runPythonCli(
          buildList {
            add("new-skill")
            payload?.let { addAll(listOf("--payload", it)) }
            if (interactive) add("--interactive")
            if (dryRun) add("--dry-run")
            addAll(listOf("--format", format.wireName))
          },
          state,
          stdinText = if (payload == "-") state.stdinText else null,
        )
      } else {
        runPythonScaffoldCli("new-skill", payload, dryRun, format, state)
      }
  }
}

@Inject
class NewCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("new", "Alias for new-skill: scaffold one skill from a payload file or interactive prompts.") {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option("--interactive", help = "Collect a skill scaffold payload via interactive prompts.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      if (interactive) {
        runPythonCli(
          buildList {
            add("new")
            payload?.let { addAll(listOf("--payload", it)) }
            if (interactive) add("--interactive")
            if (dryRun) add("--dry-run")
            addAll(listOf("--format", format.wireName))
          },
          state,
          stdinText = if (payload == "-") state.stdinText else null,
        )
      } else {
        runPythonScaffoldCli("new", payload, dryRun, format, state)
      }
  }
}

@Inject
class CreateAndFillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand(
  "create-and-fill",
  "Scaffold one governed skill, then immediately author content.md and validate it.",
) {
  private val payload by option("--payload", help = "Path to a JSON payload file (or '-' for stdin).")
  private val interactive by option("--interactive", help = "Collect a skill scaffold payload via interactive prompts.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val body by option("--body", help = "Optional authored body to write after scaffolding.")
  private val bodyFile by option("--body-file", help = "Optional file path (or '-') to read the authored body from.")
  private val editor by option("--editor", help = "Open the scaffolded content.md in \$VISUAL or \$EDITOR.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      runPythonCli(
        buildList {
          add("create-and-fill")
          payload?.let { addAll(listOf("--payload", it)) }
          if (interactive) add("--interactive")
          if (dryRun) add("--dry-run")
          body?.let { addAll(listOf("--body", it)) }
          bodyFile?.let { addAll(listOf("--body-file", it)) }
          if (editor) add("--editor")
          addAll(listOf("--format", format.wireName))
        },
        state,
        stdinText = when {
          payload == "-" -> state.stdinText
          bodyFile == "-" -> state.stdinText
          else -> null
        },
      )
  }
}

@Inject
class NewAddonCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("new-addon", "Create a governed add-on file inside an existing platform pack.") {
  private val platform by option("--platform", help = "Owning platform slug. Required unless --interactive is used.")
  private val name by option(
    "--name",
    help = "Add-on slug (without a bill- prefix). Required unless --interactive is used.",
  )
  private val body by option("--body", help = "Complete markdown body to write to the add-on file.")
  private val bodyFile by option("--body-file", help = "Path to a markdown file to copy into the add-on (or '-').")
  private val interactive by option("--interactive", help = "Prompt for platform, add-on slug, and markdown content.")
    .flag(default = false)
  private val dryRun by option("--dry-run", help = "Plan the scaffold and report the operations without touching disk.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    state.result =
      runPythonCli(
        buildList {
          add("new-addon")
          platform?.let { addAll(listOf("--platform", it)) }
          name?.let { addAll(listOf("--name", it)) }
          body?.let { addAll(listOf("--body", it)) }
          bodyFile?.let { addAll(listOf("--body-file", it)) }
          if (interactive) add("--interactive")
          if (dryRun) add("--dry-run")
          addAll(listOf("--format", format.wireName))
        },
        state,
        stdinText = if (bodyFile == "-") state.stdinText else null,
      )
  }
}

@Inject
class InstallAgentPathCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("agent-path", "Print the canonical install directory for a given agent.") {
  private val agent by argument(help = "Agent name.")

  override fun run() {
    val path = InstallOperations.agentPath(agent, state.userHome)
    state.result = CliExecutionResult(exitCode = 0, stdout = "$path\n")
  }
}

@Inject
class InstallDetectAgentsCommand(
  private val state: CliRunState,
) : DocumentedCliCommand("detect-agents", "List detected agents as 'name\\tpath' lines.") {
  override fun run() {
    val output =
      InstallOperations.detectAgentTargets(state.userHome)
        .joinToString(separator = "") { target -> "${target.name}\t${target.path}\n" }
    state.result = CliExecutionResult(exitCode = 0, stdout = output)
  }
}

@Inject
class InstallLinkSkillCommand(
  private val state: CliRunState,
) : DocumentedCliCommand(
  "link-skill",
  "Symlink a skill DIRECTORY into an agent's install directory.",
) {
  private val source by option("--source", help = "Skill directory to install.").required()
  private val targetDir by option("--target-dir", help = "Target install directory.").required()
  private val agent by option("--agent", help = "Optional agent name to label the install.").default("")

  override fun run() {
    InstallOperations.linkSkill(
      source = Path.of(source),
      targetDir = Path.of(targetDir),
      agent = agent,
    )
    state.result = CliExecutionResult(exitCode = 0, stdout = "")
  }
}

internal fun runPythonCli(arguments: List<String>, state: CliRunState, stdinText: String? = null): CliExecutionResult {
  val process = pythonProcess(arguments, state)
  if (stdinText != null) {
    process.outputStream.use { outputStream ->
      outputStream.write(stdinText.toByteArray(Charsets.UTF_8))
      outputStream.flush()
    }
  } else {
    process.outputStream.close()
  }
  return collectCliResult(process)
}

private fun runPythonScaffoldCli(
  commandName: String,
  payloadPath: String?,
  dryRun: Boolean,
  format: CliFormat,
  state: CliRunState,
): CliExecutionResult {
  val repoRoot = findRepoRoot()
  val payload = readScaffoldPayload(payloadPath, state)
  val payloadText = JsonSupport.mapToJsonString(payload + ("repo_root" to repoRoot.toString()))
  val result = runPythonCli(
    buildList {
      add(commandName)
      add("--payload")
      add("-")
      if (dryRun) add("--dry-run")
      addAll(listOf("--format", format.wireName))
    },
    state,
    stdinText = payloadText,
  )
  return normalizeScaffoldCliResult(result, payload)
}

private fun normalizeScaffoldCliResult(result: CliExecutionResult, payload: Map<String, Any?>): CliExecutionResult {
  if (result.exitCode != 0) {
    return result
  }
  val parsed = result.payload?.toMutableMap() ?: return result
  val skillName = parsed["skill_name"] as? String ?: scaffoldSkillName(payload)
  val sessionId = parsed["session_id"] as? String ?: generateScaffoldSessionId()
  parsed["started_payload"] = normalizeStartedPayload(parsed["started_payload"], payload, sessionId, skillName)
  parsed["finished_payload"] = normalizeFinishedPayload(
    parsed["finished_payload"],
    payload,
    sessionId,
    skillName,
    parsed["kind"] as? String ?: payload["kind"].orEmpty(),
    parsed["dry_run"] as? Boolean ?: false,
  )
  return CliExecutionResult(
    exitCode = result.exitCode,
    stdout = JsonSupport.mapToJsonString(parsed),
    payload = parsed,
  )
}

private fun normalizeStartedPayload(
  startedPayload: Any?,
  payload: Map<String, Any?>,
  sessionId: String,
  skillName: String,
): Map<String, Any?> {
  val normalized = (startedPayload as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
    ?: mutableMapOf()
  normalized["session_id"] = sessionId
  normalized["kind"] = payload["kind"].orEmpty()
  normalized["skill_name"] = skillName
  normalized["platform"] = payload["platform"].orEmpty()
  normalized["family"] = payload["family"].orEmpty()
  normalized["area"] = payload["area"].orEmpty()
  return normalized
}

private fun normalizeFinishedPayload(
  finishedPayload: Any?,
  payload: Map<String, Any?>,
  sessionId: String,
  skillName: String,
  kind: String,
  dryRun: Boolean,
): Map<String, Any?> {
  val normalized = (finishedPayload as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
    ?: mutableMapOf()
  normalized["session_id"] = sessionId
  normalized["kind"] = kind
  normalized["skill_name"] = skillName
  normalized["platform"] = payload["platform"].orEmpty()
  normalized["family"] = payload["family"].orEmpty()
  normalized["area"] = payload["area"].orEmpty()
  normalized["result"] = if (dryRun) "dry-run" else "success"
  normalized["duration_seconds"] = 0
  return normalized
}

private fun readScaffoldPayload(payloadPath: String?, state: CliRunState): Map<String, Any?> {
  val payloadText =
    when {
      payloadPath == null -> throw IllegalArgumentException("Either --payload or --interactive is required.")
      payloadPath == "-" -> state.stdinText.orEmpty()
      else -> Files.readString(Path.of(payloadPath))
    }
  val parsed =
    JsonSupport.parseObjectOrNull(payloadText)
      ?: throw IllegalArgumentException("Invalid JSON payload: expected an object.")
  val payload =
    JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
      ?: throw IllegalArgumentException("Invalid JSON payload: expected an object.")
  return payload.toMutableMap().apply {
    this["scaffold_payload_version"] = this["scaffold_payload_version"]?.toString()
  }
}

private fun scaffoldSkillName(payload: Map<String, Any?>): String = payload["name"].orEmpty()

private fun generateScaffoldSessionId(): String = "nss-${System.currentTimeMillis()}"

private fun Any?.orEmpty(): String = this as? String ?: ""

private fun pythonProcess(arguments: List<String>, state: CliRunState): Process {
  val repoRoot = findRepoRoot()
  val command =
    buildList {
      add("python3")
      add("-m")
      add("skill_bill.cli")
      state.dbOverride?.let {
        add("--db")
        add(it)
      }
      addAll(arguments)
    }
  val processBuilder =
    ProcessBuilder(command)
      .directory(repoRoot.toFile())
      .redirectErrorStream(false)
  val environment = processBuilder.environment()
  environment.putAll(System.getenv())
  environment.putAll(state.environment)
  environment["HOME"] = state.userHome.toString()
  environment["PYTHONPATH"] = buildPythonPath(repoRoot, environment["PYTHONPATH"])
  return processBuilder.start()
}

private fun collectCliResult(process: Process): CliExecutionResult {
  val stdout = process.inputStream.bufferedReader().readText()
  val stderr = process.errorStream.bufferedReader().readText()
  val exitCode = process.waitFor()
  val output = if (exitCode == 0) stdout else stderr.ifBlank { stdout }
  val payload =
    if (exitCode == 0) {
      JsonSupport.parseObjectOrNull(output)?.let { parsed ->
        JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
      }
    } else {
      null
    }
  return CliExecutionResult(exitCode = exitCode, stdout = output, payload = payload)
}

private fun buildPythonPath(repoRoot: Path, existing: String?): String = listOf(repoRoot.toString(), existing)
  .filterNotNull()
  .filter { it.isNotBlank() }
  .joinToString(File.pathSeparator)

private fun findRepoRoot(start: Path = Path.of("").toAbsolutePath().normalize()): Path {
  var current = start
  while (true) {
    if (Files.isDirectory(current.resolve("skill_bill")) && Files.isDirectory(current.resolve("runtime-kotlin"))) {
      return current
    }
    val parent = current.parent ?: break
    current = parent
  }
  return start
}
