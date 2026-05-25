package skillbill.cli.scaffold

import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.scaffold.wire.requireString
import skillbill.contracts.scaffold.wire.requireStringOrDefault
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.model.command.RoutingSignalsInput
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_ADD_ON
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_HORIZONTAL
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_KIND_PLATFORM_PACK
import skillbill.scaffold.model.command.SCAFFOLD_COMMAND_PAYLOAD_VERSION
import skillbill.scaffold.model.command.SUPPORTED_SCAFFOLD_COMMAND_KINDS
import skillbill.scaffold.model.command.ScaffoldCommandRequest

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
    SCAFFOLD_COMMAND_KIND_PLATFORM_OVERRIDE_PILOTED -> parsePlatformOverride(payload, version, repoRoot)
    SCAFFOLD_COMMAND_KIND_CODE_REVIEW_AREA -> parseCodeReviewArea(payload, version, repoRoot)
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
  if (kind !in SUPPORTED_SCAFFOLD_COMMAND_KINDS) {
    throw UnknownSkillKindError(
      "Scaffold payload declares unsupported kind '$kind'. Supported kinds: $SUPPORTED_SCAFFOLD_COMMAND_KINDS.",
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
  val skeletonMode = (payload["skeleton_mode"] as? String)?.takeIf { it.isNotBlank() }
  val specialistAreas = if (payload.containsKey("specialist_areas")) {
    parseStringList(payload, "specialist_areas")
  } else {
    null
  }
  val routingInput = parseRoutingSignalsInput(payload["routing_signals"])
  return ScaffoldCommandRequest.PlatformPack(
    platform = requireString(payload, "platform"),
    displayName = requireStringOrDefault(payload, "display_name", ""),
    description = requireStringOrDefault(payload, "description", ""),
    skeletonMode = skeletonMode,
    specialistAreas = specialistAreas,
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

private fun parsePlatformOverride(
  payload: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.PlatformOverride = ScaffoldCommandRequest.PlatformOverride(
  platform = requireString(payload, "platform"),
  family = requireString(payload, "family"),
  description = requireStringOrDefault(payload, "description", ""),
  contentBody = optionalString(payload, "content_body"),
  subagentSpecialists = if (payload.containsKey("subagent_specialists")) {
    parseStringList(payload, "subagent_specialists")
  } else {
    null
  },
  suppressSubagents = parseBooleanOrFalse(payload, "no_subagents"),
  nameOverride = requireOptionalNonBlank(payload, "name"),
  scaffoldPayloadVersion = version,
  repoRoot = repoRoot,
)

private fun parseCodeReviewArea(
  payload: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.CodeReviewArea = ScaffoldCommandRequest.CodeReviewArea(
  platform = requireString(payload, "platform"),
  area = requireString(payload, "area"),
  description = requireStringOrDefault(payload, "description", ""),
  contentBody = optionalString(payload, "content_body"),
  nameOverride = requireOptionalNonBlank(payload, "name"),
  scaffoldPayloadVersion = version,
  repoRoot = repoRoot,
)

private fun parseAddOn(payload: Map<String, Any?>, version: String, repoRoot: String?): ScaffoldCommandRequest.AddOn =
  ScaffoldCommandRequest.AddOn(
    name = requireString(payload, "name"),
    platform = requireString(payload, "platform"),
    description = requireStringOrDefault(payload, "description", ""),
    body = optionalString(payload, "body"),
    consumerSkillDirs = if (payload.containsKey("consumer_skill_dirs")) {
      parseStringList(payload, "consumer_skill_dirs")
    } else {
      null
    },
    scaffoldPayloadVersion = version,
    repoRoot = repoRoot,
  )
