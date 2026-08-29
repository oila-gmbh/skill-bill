package skillbill.cli.scaffold

import skillbill.application.scaffold.InstallAgentService
import skillbill.application.scaffold.ScaffoldCatalogService
import skillbill.cli.core.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.error.SkillBillRuntimeException

internal fun runNativeScaffoldWizard(args: ScaffoldWizardArgs): CliExecutionResult {
  val run = args.run
  val payload =
    try {
      collectScaffoldWizardPayload(run.state, args.scaffoldCatalogService)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), run.format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), run.format)
    }
  return runNativeScaffoldPayload(payload, run)
}

internal fun runNativeAssistedScaffoldWizard(args: AssistedScaffoldWizardArgs): CliExecutionResult {
  val run = args.run
  val payload =
    try {
      collectAssistedScaffoldWizardPayload(run.state, args.scaffoldCatalogService, args.installAgentService)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), run.format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), run.format)
    }
  return runNativeScaffoldPayload(payload, run)
}

internal fun collectAssistedScaffoldWizardPayload(
  state: CliRunState,
  scaffoldCatalogService: ScaffoldCatalogService,
  installAgentService: InstallAgentService,
): Map<String, Any?> {
  state.liveStdout(
    "Skill Bill assisted scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  val kind = normalizeWizardKind(promptRequired(state, "Kind"))
  val agent =
    promptAssistedAgent(
      state,
      installAgentService.detectAgentTargets(state.userHome, state.environment).map { target -> target.name },
    )
  state.liveStdout(
    "Assisted generator: $agent. Scaffold suggestions are deterministic local defaults; " +
      "agent-backed generation needs a structured scaffold output contract.\n",
  )
  return when (kind) {
    "platform-pack" -> assistedPlatformPackWizardPayload(state, scaffoldCatalogService.platformPackPresets())
    else -> throw IllegalArgumentException(
      "Assisted mode currently supports platform-pack scaffolds. Use the normal wizard for kind '$kind'.",
    )
  }
}

internal fun collectScaffoldWizardPayload(
  state: CliRunState,
  scaffoldCatalogService: ScaffoldCatalogService,
): Map<String, Any?> {
  state.liveStdout(
    "Skill Bill scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  return when (val kind = normalizeWizardKind(promptRequired(state, "Kind"))) {
    "horizontal" -> horizontalWizardPayload(state)
    "platform-pack" -> platformPackWizardPayload(state, scaffoldCatalogService.platformPackPresets())
    "add-on" -> addOnWizardPayload(state)
    "agent-addon" -> agentAddonWizardPayload(state)
    else -> throw IllegalArgumentException("Unsupported scaffold wizard kind '$kind'.")
  }
}

internal fun horizontalWizardPayload(state: CliRunState): Map<String, Any?> = buildMap {
  putScaffoldBase("horizontal")
  put("name", normalizeBillSkillName(promptRequired(state, "Skill name")))
  promptOptional(state, "Description").ifNotBlank { description -> put("description", description) }
}
