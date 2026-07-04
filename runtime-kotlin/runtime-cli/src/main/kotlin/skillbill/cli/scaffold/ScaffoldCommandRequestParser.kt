package skillbill.cli.scaffold

import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.scaffold.wire.requireString
import skillbill.contracts.scaffold.wire.requireStringOrDefault
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.model.command.ACTIVE_SCAFFOLD_COMMAND_KINDS
import skillbill.scaffold.model.command.RoutingSignalsInput
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_ADD_ON
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_HORIZONTAL
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_PLATFORM_PACK
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_PAYLOAD_VERSION
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import skillbill.scaffold.model.command.isRetiredPartialScaffoldCommandKindAlias
import skillbill.scaffold.model.command.rejectRetiredPartialScaffoldCommandKind

/**
 * SKILL-52.2 subtask 2: CLI raw-map → typed [ScaffoldCommandRequest] parser. Runs at the CLI
 * adapter boundary so the application + port surface no longer accepts a `Map<String, Any?>`.
 *
 * Loud-fail invariants preserved from the legacy map seam:
 *  - missing/blank `scaffold_payload_version` → [InvalidScaffoldPayloadError]
 *  - mismatched `scaffold_payload_version` → [ScaffoldPayloadVersionMismatchError]
 *  - missing/blank/unsupported `kind` → [InvalidScaffoldPayloadError] / [UnknownSkillKindError]
 *  - per-variant required-field shape errors → [InvalidScaffoldPayloadError]
 *  - field type mismatches → [InvalidScaffoldPayloadError]
 *
 * The repo-root override is carried verbatim; the runtime resolves the default fallback.
 */
fun parseScaffoldCommandRequest(payload: Map<String, Any?>): ScaffoldCommandRequest {
  val (version, kind) = validateVersionAndKind(payload)
  val repoRoot = requireOptionalNonBlank(payload, "repo_root")
  return when (kind) {
    SCAFFOLD_COMMAND_KIND_HORIZONTAL -> parseHorizontal(payload, version, repoRoot)
    SCAFFOLD_COMMAND_KIND_PLATFORM_PACK -> parsePlatformPack(payload, version, repoRoot)
    SCAFFOLD_COMMAND_KIND_ADD_ON -> parseAddOn(payload, version, repoRoot)
    else -> throw UnknownSkillKindError("Scaffold payload declares unsupported kind '$kind'.")
  }
}

/**
 * Validates the scaffold-payload envelope `(scaffold_payload_version, kind)` and returns the
 * parsed pair. Encapsulates the two top-level loud-fails (version mismatch + unsupported kind)
 * so [parseScaffoldCommandRequest] keeps its throw count at the per-function limit.
 */
private fun validateVersionAndKind(payload: Map<String, Any?>): Pair<String, String> {
  val version = requireString(payload, "scaffold_payload_version")
  if (version != SCAFFOLD_COMMAND_PAYLOAD_VERSION) {
    throw ScaffoldPayloadVersionMismatchError(
      "Scaffold payload declares 'scaffold_payload_version' '$version' " +
        "but the scaffolder expects '$SCAFFOLD_COMMAND_PAYLOAD_VERSION'.",
    )
  }
  val kind = requireString(payload, "kind")
  if (isRetiredPartialScaffoldCommandKindAlias(kind)) {
    rejectRetiredPartialScaffoldCommandKind(kind)
  }
  if (kind !in ACTIVE_SCAFFOLD_COMMAND_KINDS) {
    throw UnknownSkillKindError(
      "Scaffold payload declares unsupported kind '$kind'. Supported kinds: $ACTIVE_SCAFFOLD_COMMAND_KINDS.",
    )
  }
  return version to kind
}

private fun parseHorizontal(
  payload: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.HorizontalSkill = ScaffoldCommandRequest.HorizontalSkill(
  name = requireString(payload, "name"),
  description = requireStringOrDefault(payload, "description", ""),
  contentBody = optionalString(payload, "content_body"),
  subagentSpecialists = parseStringListOrEmpty(payload, "subagent_specialists"),
  suppressSubagents = parseBooleanOrFalse(payload, "no_subagents"),
  scaffoldPayloadVersion = version,
  repoRoot = repoRoot,
)

private fun parsePlatformPack(
  payload: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.PlatformPack {
  rejectLegacyPlatformPackSelector(payload, "skeleton_mode")
  rejectLegacyPlatformPackSelector(payload, "specialist_areas")
  val routingInput = parseRoutingSignalsInput(payload["routing_signals"])
  return ScaffoldCommandRequest.PlatformPack(
    platform = requireString(payload, "platform"),
    displayName = requireStringOrDefault(payload, "display_name", ""),
    description = requireStringOrDefault(payload, "description", ""),
    routingSignals = routingInput,
    baselineLayers = parseBaselineLayers(payload),
    subagentSpecialists = if (payload.containsKey("subagent_specialists")) {
      parseStringList(payload, "subagent_specialists")
    } else {
      null
    },
    suppressSubagents = parseBooleanOrFalse(payload, "no_subagents"),
    contentBody = optionalString(payload, "content_body"),
    nameOverride = requireOptionalNonBlank(payload, "name"),
    scaffoldPayloadVersion = version,
    repoRoot = repoRoot,
  )
}

private fun rejectLegacyPlatformPackSelector(payload: Map<String, Any?>, field: String) {
  if (!payload.containsKey(field)) return
  throw InvalidScaffoldPayloadError(
    "Scaffold payload field '$field' is no longer supported for kind 'platform-pack'. " +
      "Create the full platform pack, then remove unwanted focus areas through governed removal paths.",
  )
}

private fun parseRoutingSignalsInput(routing: Any?): RoutingSignalsInput? = when {
  routing == null -> null
  routing is Map<*, *> -> RoutingSignalsInput(
    strong = parseRoutingSignalList(routing, "strong", "routing_signals.strong"),
    tieBreakers = parseRoutingSignalList(routing, "tie_breakers", "routing_signals.tie_breakers"),
  )
  else -> throw InvalidScaffoldPayloadError(
    "Scaffold payload field 'routing_signals' must be an object when provided.",
  )
}

private fun parseAddOn(payload: Map<String, Any?>, version: String, repoRoot: String?): ScaffoldCommandRequest.AddOn =
  ScaffoldCommandRequest.AddOn(
    name = requireString(payload, "name"),
    platform = requireString(payload, "platform"),
    description = requireStringOrDefault(payload, "description", ""),
    body = optionalString(payload, "body"),
    addonLocationPath = requireOptionalNonBlank(payload, "addon_location_path"),
    consumerSkillDirs = if (payload.containsKey("consumer_skill_dirs")) {
      parseStringList(payload, "consumer_skill_dirs")
    } else {
      null
    },
    scaffoldPayloadVersion = version,
    repoRoot = repoRoot,
  )
