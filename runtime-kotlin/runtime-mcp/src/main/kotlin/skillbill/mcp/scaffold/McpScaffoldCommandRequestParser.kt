package skillbill.mcp.scaffold

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
 * SKILL-52.2 subtask 2: MCP raw-map → typed [ScaffoldCommandRequest] parser. Runs at the MCP
 * adapter boundary (JSON-RPC arg envelope → typed request) so the application + port surface
 * no longer accepts a `Map<String, Any?>` from MCP either.
 *
 * Mirrors [skillbill.cli.scaffold.parseScaffoldCommandRequest] exactly — the duplication is
 * deliberate. Both parsers loud-fail with the same legacy exception types at the same semantic
 * points so caller-facing diagnostics remain stable across CLI and MCP entry points.
 */
fun parseMcpScaffoldCommandRequest(args: Map<String, Any?>): ScaffoldCommandRequest {
  val (version, kind) = validateVersionAndKind(args)
  val repoRoot = requireOptionalNonBlank(args, "repo_root")
  return when (kind) {
    SCAFFOLD_COMMAND_KIND_HORIZONTAL -> parseHorizontal(args, version, repoRoot)
    SCAFFOLD_COMMAND_KIND_PLATFORM_PACK -> parsePlatformPack(args, version, repoRoot)
    SCAFFOLD_COMMAND_KIND_PLATFORM_OVERRIDE_PILOTED -> parsePlatformOverride(args, version, repoRoot)
    SCAFFOLD_COMMAND_KIND_CODE_REVIEW_AREA -> parseCodeReviewArea(args, version, repoRoot)
    SCAFFOLD_COMMAND_KIND_ADD_ON -> parseAddOn(args, version, repoRoot)
    else -> throw UnknownSkillKindError("Scaffold payload declares unsupported kind '$kind'.")
  }
}

/**
 * Validates the scaffold-payload envelope `(scaffold_payload_version, kind)` and returns the
 * parsed pair. Encapsulates the two top-level loud-fails (version mismatch + unsupported kind)
 * so [parseMcpScaffoldCommandRequest] keeps its throw count at the per-function limit.
 */
private fun validateVersionAndKind(args: Map<String, Any?>): Pair<String, String> {
  val version = requireString(args, "scaffold_payload_version")
  if (version != SCAFFOLD_COMMAND_PAYLOAD_VERSION) {
    throw ScaffoldPayloadVersionMismatchError(
      "Scaffold payload declares 'scaffold_payload_version' '$version' " +
        "but the scaffolder expects '$SCAFFOLD_COMMAND_PAYLOAD_VERSION'.",
    )
  }
  val kind = requireString(args, "kind")
  if (kind !in SUPPORTED_SCAFFOLD_COMMAND_KINDS) {
    throw UnknownSkillKindError(
      "Scaffold payload declares unsupported kind '$kind'. Supported kinds: $SUPPORTED_SCAFFOLD_COMMAND_KINDS.",
    )
  }
  return version to kind
}

private fun parseHorizontal(
  args: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.HorizontalSkill = ScaffoldCommandRequest.HorizontalSkill(
  name = requireString(args, "name"),
  description = requireStringOrDefault(args, "description", ""),
  contentBody = optionalString(args, "content_body"),
  subagentSpecialists = parseStringListOrEmpty(args, "subagent_specialists"),
  suppressSubagents = parseBooleanOrFalse(args, "no_subagents"),
  scaffoldPayloadVersion = version,
  repoRoot = repoRoot,
)

private fun parsePlatformPack(
  args: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.PlatformPack {
  val skeletonMode = (args["skeleton_mode"] as? String)?.takeIf { it.isNotBlank() }
  val specialistAreas = if (args.containsKey("specialist_areas")) {
    parseStringList(args, "specialist_areas")
  } else {
    null
  }
  val routingInput = parseRoutingSignalsInput(args["routing_signals"])
  return ScaffoldCommandRequest.PlatformPack(
    platform = requireString(args, "platform"),
    displayName = requireStringOrDefault(args, "display_name", ""),
    description = requireStringOrDefault(args, "description", ""),
    skeletonMode = skeletonMode,
    specialistAreas = specialistAreas,
    routingSignals = routingInput,
    baselineLayers = parseBaselineLayers(args),
    subagentSpecialists = if (args.containsKey("subagent_specialists")) {
      parseStringList(args, "subagent_specialists")
    } else {
      null
    },
    suppressSubagents = parseBooleanOrFalse(args, "no_subagents"),
    contentBody = optionalString(args, "content_body"),
    nameOverride = requireOptionalNonBlank(args, "name"),
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
  args: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.PlatformOverride = ScaffoldCommandRequest.PlatformOverride(
  platform = requireString(args, "platform"),
  family = requireString(args, "family"),
  description = requireStringOrDefault(args, "description", ""),
  contentBody = optionalString(args, "content_body"),
  subagentSpecialists = if (args.containsKey("subagent_specialists")) {
    parseStringList(args, "subagent_specialists")
  } else {
    null
  },
  suppressSubagents = parseBooleanOrFalse(args, "no_subagents"),
  nameOverride = requireOptionalNonBlank(args, "name"),
  scaffoldPayloadVersion = version,
  repoRoot = repoRoot,
)

private fun parseCodeReviewArea(
  args: Map<String, Any?>,
  version: String,
  repoRoot: String?,
): ScaffoldCommandRequest.CodeReviewArea = ScaffoldCommandRequest.CodeReviewArea(
  platform = requireString(args, "platform"),
  area = requireString(args, "area"),
  description = requireStringOrDefault(args, "description", ""),
  contentBody = optionalString(args, "content_body"),
  nameOverride = requireOptionalNonBlank(args, "name"),
  scaffoldPayloadVersion = version,
  repoRoot = repoRoot,
)

private fun parseAddOn(args: Map<String, Any?>, version: String, repoRoot: String?): ScaffoldCommandRequest.AddOn =
  ScaffoldCommandRequest.AddOn(
    name = requireString(args, "name"),
    platform = requireString(args, "platform"),
    description = requireStringOrDefault(args, "description", ""),
    body = optionalString(args, "body"),
    consumerSkillDirs = if (args.containsKey("consumer_skill_dirs")) {
      parseStringList(args, "consumer_skill_dirs")
    } else {
      null
    },
    scaffoldPayloadVersion = version,
    repoRoot = repoRoot,
  )
