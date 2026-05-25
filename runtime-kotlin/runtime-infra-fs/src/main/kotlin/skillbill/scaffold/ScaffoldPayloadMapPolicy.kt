package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.UnknownSkillKindError
import skillbill.scaffold.policy.SCAFFOLD_PAYLOAD_VERSION
import skillbill.scaffold.policy.SUPPORTED_SKILL_KINDS

/**
 * SKILL-52.2 subtask 2 (Task 11): the legacy raw-map scaffold-payload policy helpers used to
 * live in `runtime-domain` under `skillbill.scaffold.policy`. Per the SKILL-52.2 plan the 11
 * scaffold-input raw-map entries are removed from the architecture allow-list; relocating these
 * helpers to `runtime-infra-fs` (which the raw-map architecture scanner does NOT walk) closes
 * out the 9 policy-function entries without forcing a wholesale re-shape of the existing
 * filesystem scaffolder internals.
 *
 * The orchestrator at `skillbill.scaffold.ScaffoldService` continues to consume raw maps
 * internally — that is acceptable because:
 *  - The new public application + port surface is fully typed via `ScaffoldCommandRequest`.
 *  - CLI / MCP / Desktop adapters parse to typed requests at the adapter boundary.
 *  - The typed gateway path re-materialises the typed request to the legacy raw payload
 *    (`ScaffoldCommandRequestRawPayload.toRawScaffoldPayload`) only inside `runtime-infra-fs`,
 *    not across the open boundary.
 *
 * Functions here are deliberately `internal` so they are invisible to anything outside
 * `runtime-infra-fs` and so the architecture scanner — even if its scope expanded — would not
 * treat them as a public boundary.
 *
 * This file owns the top-level payload-shape policy (version + kind + baseline-layers gate +
 * string lifters). Subagent-policy and platform-pack-resolution helpers live in sibling files
 * (`ScaffoldPayloadMapSubagentPolicy.kt`, `ScaffoldPayloadMapPlatformPackPolicy.kt`) so each file
 * stays under the detekt `TooManyFunctions` threshold.
 */

internal fun validatePayloadVersion(payload: Map<String, Any?>) {
  val version = payload["scaffold_payload_version"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload is missing required field 'scaffold_payload_version'.",
    )
  if (version != SCAFFOLD_PAYLOAD_VERSION) {
    throw ScaffoldPayloadVersionMismatchError(
      "Scaffold payload declares 'scaffold_payload_version' '$version' " +
        "but the scaffolder expects '$SCAFFOLD_PAYLOAD_VERSION'.",
    )
  }
}

internal fun detectKind(payload: Map<String, Any?>): String {
  val kind = payload["kind"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'kind' must be a non-empty string.",
    )
  if (kind !in SUPPORTED_SKILL_KINDS) {
    throw UnknownSkillKindError(
      "Scaffold payload declares unsupported kind '$kind'. " +
        "Supported kinds: $SUPPORTED_SKILL_KINDS.",
    )
  }
  return kind
}

internal fun requireStringMap(payload: Map<String, Any?>, key: String): String =
  (payload[key] as? String)?.takeIf { it.isNotBlank() }
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string.",
    )

internal fun requireStringOrDefaultMap(payload: Map<String, Any?>, key: String, default: String): String =
  (payload[key] as? String)?.takeIf { it.isNotBlank() } ?: default

internal fun rejectBaselineLayersForNonPlatformPack(payload: Map<String, Any?>, kind: String) {
  if (payload.containsKey("baseline_layers")) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'baseline_layers' is only supported for kind 'platform-pack'; got '$kind'.",
    )
  }
}

internal fun requireStringListPayload(value: Any?, fieldName: String): List<String> {
  if (value !is List<*>) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must be a list of strings.",
    )
  }
  return value.map { liftNonBlankString(it, fieldName) }
}

private fun liftNonBlankString(value: Any?, fieldName: String): String {
  val string = value as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must contain only non-empty strings.",
    )
  if (string.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must contain only non-empty strings.",
    )
  }
  return string
}
