package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.ScaffoldBaselineLayerPayload
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.command.RoutingSignalsInput
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import skillbill.scaffold.model.command.rejectRetiredPartialScaffoldCommandKind

/**
 * SKILL-52.2 subtask 2: direct sealed → sealed mapping from the desktop's typed
 * [ScaffoldPayload] (commonMain) to the runtime's typed [ScaffoldCommandRequest]. The desktop
 * no longer needs a Map round-trip — it already carries typed variant data, so the gateway
 * builds the typed request directly.
 *
 * Lives in jvmMain because [ScaffoldCommandRequest] is a JVM-only runtime-domain type. The
 * legacy `ScaffoldPayload.toContractMap()` extension in commonMain is preserved for golden
 * tests until Task 13 replaces those goldens with sealed-mapping assertions.
 */
internal fun ScaffoldPayload.toCommandRequest(): ScaffoldCommandRequest = when (this) {
  is ScaffoldPayload.HorizontalSkill -> ScaffoldCommandRequest.HorizontalSkill(
    name = name,
    description = description,
    contentBody = contentBody,
    subagentSpecialists = subagentSpecialists,
    suppressSubagents = suppressSubagents,
    scaffoldPayloadVersion = ScaffoldPayload.SCAFFOLD_PAYLOAD_VERSION,
    repoRoot = repoRoot,
  )
  is ScaffoldPayload.PlatformPack -> ScaffoldCommandRequest.PlatformPack(
    platform = platform,
    displayName = displayName,
    description = description,
    routingSignals = if (strongRoutingSignals.isNotEmpty() || tieBreakers.isNotEmpty()) {
      RoutingSignalsInput(
        strong = strongRoutingSignals.takeIf { it.isNotEmpty() },
        tieBreakers = tieBreakers.takeIf { it.isNotEmpty() },
      )
    } else {
      null
    },
    baselineLayers = baselineLayers.mapIndexed { index, layer -> layer.toRuntimeBaselineLayer(index) },
    subagentSpecialists = subagentSpecialists.takeIf { it.isNotEmpty() },
    suppressSubagents = suppressSubagents,
    contentBody = contentBody,
    scaffoldPayloadVersion = ScaffoldPayload.SCAFFOLD_PAYLOAD_VERSION,
    repoRoot = repoRoot,
  )
  is ScaffoldPayload.PlatformOverride -> rejectRetiredPartialScaffoldCommandKind(kind.payloadKind)
  is ScaffoldPayload.CodeReviewArea -> rejectRetiredPartialScaffoldCommandKind(kind.payloadKind)
  is ScaffoldPayload.AddOn -> ScaffoldCommandRequest.AddOn(
    name = name,
    platform = platform,
    description = description,
    body = body,
    addonLocationPath = addonLocationPath,
    scaffoldPayloadVersion = ScaffoldPayload.SCAFFOLD_PAYLOAD_VERSION,
    repoRoot = repoRoot,
  )
  is ScaffoldPayload.AgentAddon -> ScaffoldCommandRequest.AgentAddon(
    slug = slug,
    description = description,
    agentIds = agentIds,
    consumers = consumers,
    contentBody = contentBody,
    scaffoldPayloadVersion = ScaffoldPayload.SCAFFOLD_PAYLOAD_VERSION,
    repoRoot = repoRoot,
  )
}

/**
 * SKILL-52.2 subtask 2 (CORR-F003): unsupported scope/mode strings on the desktop sealed payload
 * must surface as [InvalidScaffoldPayloadError] (a `SkillBillRuntimeException` subclass) so the
 * gateway's typed-exception branch reports `rollbackComplete = true`. The throw happens before
 * the scaffolder runs, so the repo cannot have been mutated. Error wording mirrors the
 * CLI/MCP adapter parsers (`baseline_layers[<index>].<scope|mode>` field path with the
 * supported value list) for diagnostic parity.
 */
private fun ScaffoldBaselineLayerPayload.toRuntimeBaselineLayer(index: Int): CodeReviewBaselineLayer {
  val fieldPrefix = "baseline_layers[$index]"
  val resolvedScope = CodeReviewCompositionScope.fromWireValue(scope)
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldPrefix.scope' has unsupported value '$scope'. " +
        "Supported values: ${CodeReviewCompositionScope.entries.map { it.wireValue }}.",
    )
  val resolvedMode = CodeReviewCompositionMode.fromWireValue(mode)
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldPrefix.mode' has unsupported value '$mode'. " +
        "Supported values: ${CodeReviewCompositionMode.entries.map { it.wireValue }}.",
    )
  return CodeReviewBaselineLayer(
    platform = platform,
    skill = skill,
    scope = resolvedScope,
    required = required,
    mode = resolvedMode,
  )
}
