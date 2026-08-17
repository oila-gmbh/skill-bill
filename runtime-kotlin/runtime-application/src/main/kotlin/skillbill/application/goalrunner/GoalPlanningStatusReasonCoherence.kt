package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.model.GoalPlanningStatusAlignRequest
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.ports.workflow.DecompositionManifestFileStore
import java.nio.file.Path

/**
 * Aligns status `planning_reason` with the launch-path refuse taxonomy without mutating planning rows.
 */
fun interface GoalPlanningStatusReasonCoherence {
  fun align(request: GoalPlanningStatusAlignRequest): GoalPlanningStatusSnapshot

  companion object {
    val NONE: GoalPlanningStatusReasonCoherence =
      GoalPlanningStatusReasonCoherence { request -> request.snapshot }
  }
}

@Inject
class LaunchAlignedGoalPlanningStatusReasonCoherence(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val manifestFileStore: DecompositionManifestFileStore,
) : GoalPlanningStatusReasonCoherence {
  override fun align(request: GoalPlanningStatusAlignRequest): GoalPlanningStatusSnapshot {
    if (!request.snapshot.sharedPreplanPrepared) return request.snapshot
    val recoverability = statusRecoverabilityOrRefuse {
      classifyForStatus(request)
    }
    val remedySubtaskId = request.snapshot.currentPlanningSubtaskId
      ?.takeIf { it > 0 }
      ?: request.manifest.subtasks.firstOrNull { it.status != "skipped" }?.id
      ?: 1
    return alignPlanningStatusWithLaunchRecoverability(
      snapshot = request.snapshot,
      recoverability = recoverability,
      issueKey = request.issueKey,
      remedySubtaskId = remedySubtaskId,
    )
  }

  private fun classifyForStatus(request: GoalPlanningStatusAlignRequest): GoalPlanningProvenanceRecoverability {
    val canonicalRepository = runCatching { request.repoRoot.toRealPath() }
      .getOrElse { request.repoRoot.toAbsolutePath().normalize() }
    val identity = GoalPlanningIdentity(
      request.parentWorkflowId,
      request.issueKey.trim().uppercase(),
      "repo-root-realpath-v1:$canonicalRepository",
    )
    val existing = checkpoint.findSharedPreplan(identity, request.dbPathOverride)
      ?: return GoalPlanningProvenanceRecoverability.Reuse(
        GoalPlanningContractProvenance(
          parentSpecHash = "",
          decompositionManifestHash = "",
          planningContractId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
        ),
      )
    val parentSpecPath = lexicalPath(canonicalRepository, request.manifest.parentSpecPath)
    val currentParentSpec = manifestFileStore.readText(parentSpecPath)
    val current = GoalPlanningContractProvenance(
      parentSpecHash = sha256HexUtf8(currentParentSpec),
      decompositionManifestHash = GoalPlanningSharedContextPacket.immutableDecompositionHash(request.manifest),
      planningContractId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
    )
    val packetParentSpec = planningPacketParentSpec(existing)
    val savedParentSpec = if (existing.provenance.parentSpecHash == current.parentSpecHash) {
      currentParentSpec
    } else {
      packetParentSpec
    }
    return classifyGoalPlanningProvenanceRecoverability(
      existing = existing,
      current = current,
      savedParentSpec = savedParentSpec,
      currentParentSpec = currentParentSpec,
    )
  }

  private fun planningPacketParentSpec(existing: SharedGoalPreplanCheckpoint): String? {
    val packet = JsonSupport.parseObjectOrNull(existing.preplanPayload)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("produced_outputs")
      ?.let(JsonSupport::anyToStringAnyMap)
      ?.get("_goal_planning_shared_context")
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: return null
    return packet["parent_spec"] as? String
  }

  private fun lexicalPath(canonicalRepository: Path, governingPath: String): Path {
    val path = Path.of(governingPath)
    return (if (path.isAbsolute) path else canonicalRepository.resolve(path)).toAbsolutePath().normalize()
  }
}
