package skillbill.scaffold.policy

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.policy.model.OptionalSubagents

/**
 * SKILL-52.1 subtask 2: pure-policy rules for specialist-subagent payload fields. Mirrors the
 * historical seam in `runtime-infra-fs` ScaffoldService — when the wire payload requests
 * orchestrator subagents, this is where shape, naming, kind-eligibility, and rejection rules live.
 *
 * Loud-fails preserve the existing [InvalidScaffoldPayloadError] type so caller-facing errors
 * do not change.
 */

/**
 * Parses the optional subagent-specialist fields from the payload, applying the naming pattern,
 * duplicate rejection, and orchestrator-only enforcement.
 */
fun optionalSpecialistSubagents(payload: Map<String, Any?>, kind: String): OptionalSubagents {
  val rawSpecialists = payload["subagent_specialists"] ?: emptyList<String>()
  val rawSuppressed = payload["no_subagents"] ?: false
  val rawList = rawSpecialists as? List<*>
    ?: failSubagentPolicy("Scaffold payload field 'subagent_specialists' must be a list of strings.")
  val specialists = parseSubagentNames(rawList)
  val suppressed = rawSuppressed as? Boolean
    ?: failSubagentPolicy("Scaffold payload field 'no_subagents' must be a boolean when provided.")
  enforceSubagentInvariants(kind, specialists, suppressed)
  return OptionalSubagents(specialists = specialists, suppressed = suppressed)
}

/**
 * Re-runs subagent-specialist parsing on leaf kinds to surface the orchestrator-only rule when a
 * leaf kind attempts to declare specialists. No-op when the payload does not declare them.
 */
fun rejectLeafSubagentSpecialists(payload: Map<String, Any?>, kind: String) {
  if (payload["subagent_specialists"] != null) {
    optionalSpecialistSubagents(payload, kind)
  }
}

/**
 * Rejects `baseline_layers` for any kind other than `platform-pack`. The field is only meaningful
 * when composing a new platform-pack baseline review.
 */
fun rejectBaselineLayersForNonPlatformPack(payload: Map<String, Any?>, kind: String) {
  if (payload.containsKey("baseline_layers")) {
    failSubagentPolicy(
      "Scaffold payload field 'baseline_layers' is only supported for kind 'platform-pack'; got '$kind'.",
    )
  }
}

private fun parseSubagentNames(rawList: List<*>): List<String> {
  val specialists = mutableListOf<String>()
  val seen = mutableSetOf<String>()
  rawList.forEach { raw ->
    val specialist = (raw as? String)?.takeUnless(String::isBlank)
      ?: failSubagentPolicy(
        "Scaffold payload field 'subagent_specialists' must contain only non-empty strings.",
      )
    if (!SUBAGENT_NAME_PATTERN.matches(specialist)) {
      failSubagentPolicy(
        "Scaffold payload field 'subagent_specialists' contains invalid name '$specialist'; " +
          "names must match '^[a-z][a-z0-9-]*$'.",
      )
    }
    if (!seen.add(specialist)) {
      failSubagentPolicy(
        "Scaffold payload field 'subagent_specialists' contains duplicate name '$specialist'.",
      )
    }
    specialists += specialist
  }
  return specialists
}

private fun enforceSubagentInvariants(kind: String, specialists: List<String>, suppressed: Boolean) {
  if (suppressed && specialists.isNotEmpty()) {
    failSubagentPolicy(
      "Scaffold payload may not set 'no_subagents=true' together with a non-empty 'subagent_specialists' list.",
    )
  }
  if (specialists.isNotEmpty() && kind !in ORCHESTRATOR_KINDS_FOR_SUBAGENTS) {
    failSubagentPolicy(
      "subagent_specialists is only valid for orchestrator kinds " +
        "(horizontal, platform-override-piloted, platform-pack); got $kind",
    )
  }
}

private fun failSubagentPolicy(message: String): Nothing = throw InvalidScaffoldPayloadError(message)
