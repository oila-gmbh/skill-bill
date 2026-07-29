package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION
import skillbill.workflow.model.CodeReviewExecutionMode

const val FEATURE_TASK_RUNTIME_COMPLEXITY_MAX: Int = 100

data class FeatureTaskRuntimeComplexitySignals(
  val taskCount: Int,
  val dependencyDepth: Int,
  val moduleBreadth: Int,
  val boundaryBreadth: Int,
  val persistenceOrMigration: Boolean,
  val securityOrPrivacy: Boolean,
  val concurrencyOrLifecycle: Boolean,
  val processBoundaryOrCrashRecovery: Boolean,
  val platformCount: Int,
  val expectedChangedPathCount: Int,
) {
  init {
    require(taskCount in 1..128)
    require(dependencyDepth in 0..32)
    require(moduleBreadth in 1..64)
    require(boundaryBreadth in 1..64)
    require(platformCount in 1..16)
    require(expectedChangedPathCount in 1..512)
  }

  fun boundedScore(): Int {
    val breadth = (moduleBreadth * 3 + boundaryBreadth * 4 + platformCount * 4).coerceAtMost(35)
    val shape = (taskCount * 2 + dependencyDepth * 4 + expectedChangedPathCount / 8).coerceAtMost(25)
    val risk = listOf(
      persistenceOrMigration,
      securityOrPrivacy,
      concurrencyOrLifecycle,
      processBoundaryOrCrashRecovery,
    ).count { it } * 12
    return (breadth + shape + risk).coerceAtMost(FEATURE_TASK_RUNTIME_COMPLEXITY_MAX)
  }
}

data class FeatureTaskRuntimeSizingPolicy(
  val contractVersion: String = FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION,
  val mediumMinScore: Int = 20,
  val largeMinScore: Int = 45,
  val decompositionMinScore: Int = 70,
  val maxDirectScore: Int = 69,
) {
  init {
    require(contractVersion == FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION)
    require(mediumMinScore in 0..100)
    require(largeMinScore in mediumMinScore..100)
    require(decompositionMinScore in largeMinScore..100)
    require(maxDirectScore in 0 until decompositionMinScore)
  }
}

enum class FeatureTaskRuntimeDecompositionRequirement { DIRECT_ALLOWED, DECOMPOSITION_REQUIRED }

data class FeatureTaskRuntimeSizingDecision(
  val featureSize: FeatureTaskRuntimeFeatureSize,
  val score: Int,
  val decompositionRequirement: FeatureTaskRuntimeDecompositionRequirement,
  val rationale: List<String>,
)

object FeatureTaskRuntimeSizingPolicyResolver {
  fun resolve(
    signals: FeatureTaskRuntimeComplexitySignals,
    policy: FeatureTaskRuntimeSizingPolicy = FeatureTaskRuntimeSizingPolicy(),
  ): FeatureTaskRuntimeSizingDecision {
    val score = signals.boundedScore()
    val size = when {
      score >= policy.largeMinScore -> FeatureTaskRuntimeFeatureSize.LARGE
      score >= policy.mediumMinScore -> FeatureTaskRuntimeFeatureSize.MEDIUM
      else -> FeatureTaskRuntimeFeatureSize.SMALL
    }
    val crossBoundaryHighRisk =
      signals.moduleBreadth >= 3 &&
        signals.boundaryBreadth >= 3 &&
        signals.persistenceOrMigration &&
        (signals.securityOrPrivacy || signals.processBoundaryOrCrashRecovery) &&
        signals.concurrencyOrLifecycle
    val decompositionRequired = score >= policy.decompositionMinScore || crossBoundaryHighRisk
    return FeatureTaskRuntimeSizingDecision(
      featureSize = if (crossBoundaryHighRisk) FeatureTaskRuntimeFeatureSize.LARGE else size,
      score = score,
      decompositionRequirement = if (decompositionRequired) {
        FeatureTaskRuntimeDecompositionRequirement.DECOMPOSITION_REQUIRED
      } else {
        FeatureTaskRuntimeDecompositionRequirement.DIRECT_ALLOWED
      },
      rationale = buildList {
        add("bounded_score=$score")
        if (crossBoundaryHighRisk) add("cross_boundary_persistence_lifecycle_risk")
        if (decompositionRequired) add("decomposition_policy_exceeded")
      },
    )
  }
}

data class FeatureTaskRuntimeGovernedDirectOverride(
  val overrideId: String,
  val policyVersion: String,
  val rationale: String,
  val persisted: Boolean,
) {
  init {
    require(overrideId.isNotBlank() && overrideId.length <= 128)
    require(policyVersion == FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION)
    require(rationale.isNotBlank() && rationale.length <= 512)
  }
}

enum class FeatureTaskRuntimePlanAdvance { IMPLEMENT, REENTER_PLAN_FOR_DECOMPOSITION }

object FeatureTaskRuntimeDirectPlanGate {
  fun resolve(
    decision: FeatureTaskRuntimeSizingDecision,
    override: FeatureTaskRuntimeGovernedDirectOverride? = null,
  ): FeatureTaskRuntimePlanAdvance =
    if (
      decision.decompositionRequirement == FeatureTaskRuntimeDecompositionRequirement.DECOMPOSITION_REQUIRED &&
      override?.persisted != true
    ) {
      FeatureTaskRuntimePlanAdvance.REENTER_PLAN_FOR_DECOMPOSITION
    } else {
      FeatureTaskRuntimePlanAdvance.IMPLEMENT
    }
}

enum class FeatureTaskRuntimeReviewSubstanceDepth { STANDARD, SPECIALIST, MULTI_SPECIALIST }
enum class FeatureTaskRuntimeResolvedReviewMode { INLINE, DELEGATED, PARALLEL_SPECIALIST }

data class FeatureTaskRuntimeResolvedReviewPolicy(
  val minimumDepth: FeatureTaskRuntimeReviewSubstanceDepth,
  val executionMode: FeatureTaskRuntimeResolvedReviewMode,
  val requiredSpecialistAreas: Set<String>,
  val rationale: List<String>,
)

object FeatureTaskRuntimeAdaptiveReviewPolicy {
  fun resolve(
    sizing: FeatureTaskRuntimeSizingDecision,
    signals: FeatureTaskRuntimeComplexitySignals,
    requestedMode: CodeReviewExecutionMode,
  ): FeatureTaskRuntimeResolvedReviewPolicy {
    val riskAreas = buildSet {
      if (signals.persistenceOrMigration) add("persistence")
      if (signals.securityOrPrivacy) add("security")
      if (signals.concurrencyOrLifecycle || signals.processBoundaryOrCrashRecovery) add("platform-correctness")
    }
    val depth = when {
      riskAreas.size >= 2 || signals.boundaryBreadth >= 5 ->
        FeatureTaskRuntimeReviewSubstanceDepth.MULTI_SPECIALIST
      riskAreas.isNotEmpty() || sizing.featureSize == FeatureTaskRuntimeFeatureSize.LARGE ->
        FeatureTaskRuntimeReviewSubstanceDepth.SPECIALIST
      else -> FeatureTaskRuntimeReviewSubstanceDepth.STANDARD
    }
    val minimumMode = when (depth) {
      FeatureTaskRuntimeReviewSubstanceDepth.MULTI_SPECIALIST ->
        FeatureTaskRuntimeResolvedReviewMode.PARALLEL_SPECIALIST
      FeatureTaskRuntimeReviewSubstanceDepth.SPECIALIST ->
        FeatureTaskRuntimeResolvedReviewMode.DELEGATED
      FeatureTaskRuntimeReviewSubstanceDepth.STANDARD ->
        FeatureTaskRuntimeResolvedReviewMode.INLINE
    }
    val requested = when (requestedMode) {
      CodeReviewExecutionMode.INLINE -> FeatureTaskRuntimeResolvedReviewMode.INLINE
      CodeReviewExecutionMode.DELEGATED -> FeatureTaskRuntimeResolvedReviewMode.DELEGATED
      CodeReviewExecutionMode.AUTO -> minimumMode
    }
    val resolved = if (requested.ordinal < minimumMode.ordinal) minimumMode else requested
    return FeatureTaskRuntimeResolvedReviewPolicy(
      minimumDepth = depth,
      executionMode = resolved,
      requiredSpecialistAreas = riskAreas,
      rationale = listOf("minimum_depth=${depth.name.lowercase()}", "risk_area_count=${riskAreas.size}"),
    )
  }
}

enum class FeatureTaskRuntimeFocusedQualityCategory {
  FORMAT,
  COMPILATION,
  STATIC_ANALYSIS,
  SCHEMA_PARITY,
  MIGRATION,
  FOCUSED_TEST,
}

data class FeatureTaskRuntimeFocusedQualityCheck(
  val checkId: String,
  val category: FeatureTaskRuntimeFocusedQualityCategory,
  val ownedPaths: List<String>,
  val checkerSkill: String,
) {
  init {
    require(checkId.isNotBlank() && checkId.length <= 128)
    require(ownedPaths.isNotEmpty() && ownedPaths.size <= 512)
    require(ownedPaths == ownedPaths.distinct().sorted())
    require(ownedPaths.all(::isNormalizedRepositoryPath))
    require(checkerSkill.isNotBlank() && checkerSkill.length <= 128)
  }
}

data class FeatureTaskRuntimeFocusedQualityCheckpoint(
  val checkpointFingerprint: String,
  val semanticFingerprint: String,
  val checks: List<FeatureTaskRuntimeFocusedQualityCheck>,
  val passed: Boolean,
) {
  init {
    require(checkpointFingerprint.isNotBlank() && semanticFingerprint.isNotBlank())
    require(checks.map { it.checkId }.distinct().size == checks.size)
  }

  fun reusableFor(fingerprint: String): Boolean = passed && semanticFingerprint == fingerprint
}

enum class FeatureTaskRuntimeFocusedQualityDisposition { REUSED, PASSED, REPAIR_REQUIRED }

data class FeatureTaskRuntimeFocusedQualityOutcome(
  val disposition: FeatureTaskRuntimeFocusedQualityDisposition,
  val checkpoint: FeatureTaskRuntimeFocusedQualityCheckpoint?,
  val repairBatch: FeatureTaskRuntimeQualityRepairBatch?,
) {
  init {
    require((disposition == FeatureTaskRuntimeFocusedQualityDisposition.REPAIR_REQUIRED) == (repairBatch != null))
    require((disposition != FeatureTaskRuntimeFocusedQualityDisposition.REPAIR_REQUIRED) == (checkpoint != null))
  }
}

data class FeatureTaskRuntimeQualityRepairItem(
  val itemId: String,
  val checkId: String,
  val category: FeatureTaskRuntimeFocusedQualityCategory,
  val boundedDiagnostic: String,
)

data class FeatureTaskRuntimeQualityRepairBatch(
  val batchId: String,
  val checkpointFingerprint: String,
  val attempt: Int,
  val items: List<FeatureTaskRuntimeQualityRepairItem>,
) {
  init {
    require(batchId.isNotBlank() && checkpointFingerprint.isNotBlank())
    require(attempt in 1..8)
    require(items.isNotEmpty() && items.size <= 128)
    require(items.map { it.itemId }.distinct().size == items.size)
    require(items.all { it.boundedDiagnostic.isNotBlank() && it.boundedDiagnostic.length <= 512 })
  }
}

data class FeatureTaskRuntimeAdaptiveDecisionRecord(
  val decisionId: String,
  val sizingDecision: FeatureTaskRuntimeSizingDecision,
  val directOverride: FeatureTaskRuntimeGovernedDirectOverride?,
  val reviewPolicy: FeatureTaskRuntimeResolvedReviewPolicy,
  val focusedQualityOutcome: FeatureTaskRuntimeFocusedQualityOutcome?,
) {
  init {
    require(decisionId.isNotBlank() && decisionId.length <= 128)
  }

  fun withFocusedQuality(outcome: FeatureTaskRuntimeFocusedQualityOutcome): FeatureTaskRuntimeAdaptiveDecisionRecord =
    copy(focusedQualityOutcome = outcome)
}

private fun isNormalizedRepositoryPath(path: String): Boolean =
  path.isNotBlank() &&
    !path.startsWith("/") &&
    '\\' !in path &&
    path.split('/').none { it.isBlank() || it == "." || it == ".." }
