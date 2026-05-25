package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.scaffold.policy.ORCHESTRATOR_KINDS_FOR_SUBAGENTS
import skillbill.scaffold.policy.SUBAGENT_NAME_PATTERN
import skillbill.scaffold.policy.model.OptionalSubagents

/**
 * SKILL-52.2 subtask 2 (Task 11): subagent-policy helpers split out of `ScaffoldPayloadMapPolicy`
 * so each file stays under the detekt `TooManyFunctions` threshold and each function stays under
 * the per-function `ThrowsCount` cap. See the doc comment in `ScaffoldPayloadMapPolicy.kt` for the
 * relocation rationale.
 */

internal fun optionalSpecialistSubagents(payload: Map<String, Any?>, kind: String): OptionalSubagents {
  val rawSpecialists = payload["subagent_specialists"] ?: emptyList<String>()
  val rawSuppressed = payload["no_subagents"] ?: false
  val rawList = rawSpecialists as? List<*>
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'subagent_specialists' must be a list of strings.",
    )
  val suppressed = rawSuppressed as? Boolean
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'no_subagents' must be a boolean when provided.",
    )
  val specialists = parseSubagentNames(rawList)
  enforceSubagentInvariants(kind, specialists, suppressed)
  return OptionalSubagents(specialists = specialists, suppressed = suppressed)
}

internal fun rejectLeafSubagentSpecialists(payload: Map<String, Any?>, kind: String) {
  if (payload["subagent_specialists"] != null) {
    optionalSpecialistSubagents(payload, kind)
  }
}

private fun parseSubagentNames(rawList: List<*>): List<String> {
  val specialists = mutableListOf<String>()
  val seen = mutableSetOf<String>()
  rawList.forEach { raw ->
    val specialist = liftSubagentName(raw)
    enforceSubagentNameNotDuplicate(specialist, seen)
    specialists += specialist
  }
  return specialists
}

private fun liftSubagentName(raw: Any?): String {
  val specialist = (raw as? String)?.takeUnless(String::isBlank)
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'subagent_specialists' must contain only non-empty strings.",
    )
  if (!SUBAGENT_NAME_PATTERN.matches(specialist)) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'subagent_specialists' contains invalid name '$specialist'; " +
        "names must match '^[a-z][a-z0-9-]*$'.",
    )
  }
  return specialist
}

private fun enforceSubagentNameNotDuplicate(specialist: String, seen: MutableSet<String>) {
  if (!seen.add(specialist)) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'subagent_specialists' contains duplicate name '$specialist'.",
    )
  }
}

private fun enforceSubagentInvariants(kind: String, specialists: List<String>, suppressed: Boolean) {
  if (suppressed && specialists.isNotEmpty()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload may not set 'no_subagents=true' together with a non-empty 'subagent_specialists' list.",
    )
  }
  if (specialists.isNotEmpty() && kind !in ORCHESTRATOR_KINDS_FOR_SUBAGENTS) {
    throw InvalidScaffoldPayloadError(
      "subagent_specialists is only valid for orchestrator kinds " +
        "(horizontal, platform-override-piloted, platform-pack); got $kind",
    )
  }
}
