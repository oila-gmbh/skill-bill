package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths
import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.ports.goalrunner.GoalPlanningContextDiscovery
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.ports.workflow.DecompositionManifestFileStore
import skillbill.workflow.model.DecompositionManifest
import java.nio.file.Path

/**
 * Aligns status `planning_reason` with the launch-path refuse taxonomy without mutating planning rows.
 */
fun interface GoalPlanningStatusReasonCoherence {
  fun align(
    snapshot: GoalPlanningStatusSnapshot,
    parentWorkflowId: String,
    issueKey: String,
    manifest: DecompositionManifest,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalPlanningStatusSnapshot

  companion object {
    val NONE: GoalPlanningStatusReasonCoherence =
      GoalPlanningStatusReasonCoherence { snapshot, _, _, _, _, _ -> snapshot }
  }
}

@Inject
class LaunchAlignedGoalPlanningStatusReasonCoherence(
  private val checkpoint: GoalPlanningPreparationCheckpoint,
  private val contextDiscovery: GoalPlanningContextDiscovery,
  private val manifestFileStore: DecompositionManifestFileStore,
) : GoalPlanningStatusReasonCoherence {
  override fun align(
    snapshot: GoalPlanningStatusSnapshot,
    parentWorkflowId: String,
    issueKey: String,
    manifest: DecompositionManifest,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalPlanningStatusSnapshot {
    if (!snapshot.sharedPreplanPrepared) return snapshot
    val recoverability = runCatching {
      classifyForStatus(parentWorkflowId, issueKey, manifest, repoRoot, dbPathOverride)
    }.getOrNull() ?: return snapshot
    val remedySubtaskId = snapshot.currentPlanningSubtaskId
      ?.takeIf { it > 0 }
      ?: manifest.subtasks.firstOrNull { it.status != "skipped" }?.id
      ?: 1
    return alignPlanningStatusWithLaunchRecoverability(
      snapshot = snapshot,
      recoverability = recoverability,
      issueKey = issueKey,
      remedySubtaskId = remedySubtaskId,
    )
  }

  private fun classifyForStatus(
    parentWorkflowId: String,
    issueKey: String,
    manifest: DecompositionManifest,
    repoRoot: Path,
    dbPathOverride: String?,
  ): GoalPlanningProvenanceRecoverability {
    val canonicalRepository = runCatching { repoRoot.toRealPath() }
      .getOrElse { repoRoot.toAbsolutePath().normalize() }
    val identity = GoalPlanningIdentity(
      parentWorkflowId,
      issueKey.trim().uppercase(),
      "repo-root-realpath-v1:$canonicalRepository",
    )
    val existing = checkpoint.findSharedPreplan(identity, dbPathOverride) ?: return GoalPlanningProvenanceRecoverability.Reuse(
      GoalPlanningContractProvenance(
        parentSpecHash = "",
        decompositionManifestHash = "",
        planningContractId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
      ),
    )
    val parentSpecPath = lexicalPath(canonicalRepository, manifest.parentSpecPath)
    val currentParentSpec = manifestFileStore.readText(parentSpecPath)
    val current = GoalPlanningContractProvenance(
      parentSpecHash = sha256HexUtf8(currentParentSpec),
      decompositionManifestHash = GoalPlanningSharedContextPacket.immutableDecompositionHash(manifest),
      planningContractId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
    )
    val packetParentSpec = planningPacketParentSpec(existing)
    val savedParentSpec = if (existing.provenance.parentSpecHash == current.parentSpecHash) {
      currentParentSpec
    } else {
      packetParentSpec
    }
    val selected = selectedBoundaryHeadingIds(existing.preplanPayload)
    val freshCatalogHeadingIds = if (selected.isEmpty()) {
      emptySet()
    } else {
      contextDiscovery.discover(canonicalRepository).boundaryCatalog.mapTo(linkedSetOf()) { it.headingId }
    }
    return classifyGoalPlanningProvenanceRecoverability(
      existing = existing,
      current = current,
      savedParentSpec = savedParentSpec,
      currentParentSpec = currentParentSpec,
      freshCatalogHeadingIds = freshCatalogHeadingIds,
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
