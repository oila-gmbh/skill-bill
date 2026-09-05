package skillbill.cli.scaffold

import skillbill.application.scaffold.InstallAgentService
import skillbill.cli.kernel.CliRunState
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliRunInputs
import skillbill.error.SkillBillRuntimeException
import skillbill.ports.scaffold.ScaffoldCatalogGateway

internal fun runNativeScaffoldWizard(args: ScaffoldWizardArgs): CliExecutionResult {
  val run = args.run
  val payload =
    try {
      collectScaffoldWizardPayload(run.state, run.inputs, args.scaffoldCatalogGateway)
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
      collectAssistedScaffoldWizardPayload(run.state, run.inputs, args.scaffoldCatalogGateway, args.installAgentService)
    } catch (error: SkillBillRuntimeException) {
      return errorResult(error.message.orEmpty(), run.format)
    } catch (error: IllegalArgumentException) {
      return errorResult(error.message.orEmpty(), run.format)
    }
  return runNativeScaffoldPayload(payload, run)
}

internal fun collectAssistedScaffoldWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  scaffoldCatalogGateway: ScaffoldCatalogGateway,
  installAgentService: InstallAgentService,
): Map<String, Any?> {
  inputs.liveStdout(
    "Skill Bill assisted scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  val kind = normalizeWizardKind(promptRequired(state, inputs, "Kind"))
  val agent =
    promptAssistedAgent(
      state,
      inputs,
      installAgentService.detectAgentTargets(inputs.userHome, inputs.environment).map { target -> target.name },
    )
  inputs.liveStdout(
    "Assisted generator: $agent. Scaffold suggestions are deterministic local defaults; " +
      "agent-backed generation needs a structured scaffold output contract.\n",
  )
  return when (kind) {
    "platform-pack" -> assistedPlatformPackWizardPayload(state, inputs, scaffoldCatalogGateway.platformPackPresets())
    else -> throw IllegalArgumentException(
      "Assisted mode currently supports platform-pack scaffolds. Use the normal wizard for kind '$kind'.",
    )
  }
}

internal fun collectScaffoldWizardPayload(
  state: CliRunState,
  inputs: CliRunInputs,
  scaffoldCatalogGateway: ScaffoldCatalogGateway,
): Map<String, Any?> {
  inputs.liveStdout(
    "Skill Bill scaffold wizard\n" +
      "Kind: 1 horizontal, 2 platform-pack, 3 add-on, 4 agent-addon\n\n",
  )
  return when (val kind = normalizeWizardKind(promptRequired(state, inputs, "Kind"))) {
    "horizontal" -> horizontalWizardPayload(state, inputs)
    "platform-pack" -> platformPackWizardPayload(state, inputs, scaffoldCatalogGateway.platformPackPresets())
    "add-on" -> addOnWizardPayload(state, inputs)
    "agent-addon" -> agentAddonWizardPayload(state, inputs)
    else -> throw IllegalArgumentException("Unsupported scaffold wizard kind '$kind'.")
  }
}

internal fun horizontalWizardPayload(state: CliRunState, inputs: CliRunInputs): Map<String, Any?> = buildMap {
  putScaffoldBase("horizontal")
  put("name", normalizeBillSkillName(promptRequired(state, inputs, "Skill name")))
  promptOptional(state, inputs, "Description").ifNotBlank { description -> put("description", description) }
}
