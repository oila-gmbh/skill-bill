package skillbill.application.goalrunner.planning
import skillbill.application.goalplanning.sha256HexUtf8
import skillbill.contracts.JsonCodec
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

internal sealed interface GoalPlanningProvenanceRecoverability {
  class Reuse(val provenance: GoalPlanningContractProvenance) : GoalPlanningProvenanceRecoverability
  class StaleValid(val provenance: GoalPlanningContractProvenance) : GoalPlanningProvenanceRecoverability
  class Irrecoverable(val recoveryKind: GoalPlanningRecoveryKind) : GoalPlanningProvenanceRecoverability
}

internal fun classifyGoalPlanningProvenanceRecoverability(
  existing: SharedGoalPreplanCheckpoint?,
  current: GoalPlanningContractProvenance,
  savedParentSpec: String?,
  currentParentSpec: String,
): GoalPlanningProvenanceRecoverability {
  if (existing == null) return GoalPlanningProvenanceRecoverability.Reuse(current)
  val saved = existing.provenance
  val contractCompatible =
    saved.planningContractId == current.planningContractId &&
      saved.planningContractVersion == current.planningContractVersion &&
      saved.phaseOutputContractId == current.phaseOutputContractId &&
      saved.phaseOutputContractVersion == current.phaseOutputContractVersion
  if (!contractCompatible) {
    return GoalPlanningProvenanceRecoverability.Irrecoverable(GoalPlanningRecoveryKind.HARD_RESET)
  }
  val valid = saved.decompositionManifestHash == current.decompositionManifestHash &&
    savedParentSpec != null &&
    sha256HexUtf8(savedParentSpec) == saved.parentSpecHash &&
    sha256HexUtf8(existing.preplanPayload) == existing.payloadSha256
  if (!valid) return GoalPlanningProvenanceRecoverability.Irrecoverable(GoalPlanningRecoveryKind.SCOPED_REPLAN)
  val fresh = GoalPlanningSpecCanonicalization.canonical(savedParentSpec) ==
    GoalPlanningSpecCanonicalization.canonical(currentParentSpec)
  return if (fresh) {
    GoalPlanningProvenanceRecoverability.Reuse(saved)
  } else {
    GoalPlanningProvenanceRecoverability.StaleValid(saved)
  }
}

fun preplanProseValue(preplanPayload: String): String = runCatching {
  JsonCodec.parseObjectOrNull(preplanPayload)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.get("produced_outputs")
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.get("value")
    ?.toString()
    .orEmpty()
}.getOrDefault("")

fun preplanProsePrompt(preplanPayload: String): String? = runCatching {
  JsonCodec.parseObjectOrNull(preplanPayload)
    ?.let(JsonCodec::jsonElementToValue)
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.get("produced_outputs")
    ?.let(JsonCodec::anyToStringAnyMap)
    ?.get("prompt")
    ?.toString()
    ?.takeIf(String::isNotBlank)
}.getOrNull()

fun preplanProseValueHash(preplanPayload: String): String = sha256HexUtf8(preplanProseValue(preplanPayload))

fun preplanProsePromptHash(preplanPayload: String): String = sha256HexUtf8(preplanProsePrompt(preplanPayload).orEmpty())
