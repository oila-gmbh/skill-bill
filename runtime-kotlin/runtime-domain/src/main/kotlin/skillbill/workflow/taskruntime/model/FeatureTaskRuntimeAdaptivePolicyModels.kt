package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION
import skillbill.workflow.model.CodeReviewExecutionMode

const val FEATURE_TASK_RUNTIME_COMPLEXITY_MAX: Int = 100
private const val MAX_TASK_COUNT: Int = 128
private const val MAX_DEPENDENCY_DEPTH: Int = 32
private const val MAX_MODULE_BREADTH: Int = 64
private const val MAX_BOUNDARY_BREADTH: Int = 64
private const val MAX_PLATFORM_COUNT: Int = 16
private const val MAX_CHANGED_PATH_COUNT: Int = 512
private const val MODULE_BREADTH_WEIGHT: Int = 3
private const val BOUNDARY_BREADTH_WEIGHT: Int = 4
private const val PLATFORM_COUNT_WEIGHT: Int = 4
private const val MAX_BREADTH_SCORE: Int = 35
private const val TASK_COUNT_WEIGHT: Int = 2
private const val DEPENDENCY_DEPTH_WEIGHT: Int = 4
private const val CHANGED_PATH_SCORE_DIVISOR: Int = 8
private const val MAX_SHAPE_SCORE: Int = 25
private const val RISK_WEIGHT: Int = 12
private const val CROSS_BOUNDARY_BREADTH_MINIMUM: Int = 3
private const val MULTI_SPECIALIST_RISK_COUNT: Int = 2
private const val MULTI_SPECIALIST_BOUNDARY_BREADTH: Int = 5
private const val ADAPTIVE_ID_MAX_LENGTH: Int = 128
private const val ADAPTIVE_SUMMARY_MAX_LENGTH: Int = 512
private const val QUALITY_REPAIR_MAX_ATTEMPTS: Int = 8
private const val QUALITY_REPAIR_MAX_ITEMS: Int = 128

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
    require(taskCount in 1..MAX_TASK_COUNT)
    require(dependencyDepth in 0..MAX_DEPENDENCY_DEPTH)
    require(moduleBreadth in 1..MAX_MODULE_BREADTH)
    require(boundaryBreadth in 1..MAX_BOUNDARY_BREADTH)
    require(platformCount in 1..MAX_PLATFORM_COUNT)
    require(expectedChangedPathCount in 1..MAX_CHANGED_PATH_COUNT)
  }

  fun boundedScore(): Int {
    val breadth = (
      moduleBreadth * MODULE_BREADTH_WEIGHT +
        boundaryBreadth * BOUNDARY_BREADTH_WEIGHT +
        platformCount * PLATFORM_COUNT_WEIGHT
      ).coerceAtMost(MAX_BREADTH_SCORE)
    val shape = (
      taskCount * TASK_COUNT_WEIGHT +
        dependencyDepth * DEPENDENCY_DEPTH_WEIGHT +
        expectedChangedPathCount / CHANGED_PATH_SCORE_DIVISOR
      ).coerceAtMost(MAX_SHAPE_SCORE)
    val risk = listOf(
      persistenceOrMigration,
      securityOrPrivacy,
      concurrencyOrLifecycle,
      processBoundaryOrCrashRecovery,
    ).count { it } * RISK_WEIGHT
    return (breadth + shape + risk).coerceAtMost(FEATURE_TASK_RUNTIME_COMPLEXITY_MAX)
  }

  fun toBriefingLine(): String = "tasks=$taskCount, dependency_depth=$dependencyDepth, modules=$moduleBreadth, " +
    "boundaries=$boundaryBreadth, persistence_or_migration=$persistenceOrMigration, " +
    "security_or_privacy=$securityOrPrivacy, concurrency_or_lifecycle=$concurrencyOrLifecycle, " +
    "process_or_recovery=$processBoundaryOrCrashRecovery, platforms=$platformCount, " +
    "expected_changed_paths=$expectedChangedPathCount"

  companion object {
    /** Source-compatible fixture default; governed production projections must always parse explicit signals. */
    val MINIMUM: FeatureTaskRuntimeComplexitySignals = FeatureTaskRuntimeComplexitySignals(
      taskCount = 1,
      dependencyDepth = 0,
      moduleBreadth = 1,
      boundaryBreadth = 1,
      persistenceOrMigration = false,
      securityOrPrivacy = false,
      concurrencyOrLifecycle = false,
      processBoundaryOrCrashRecovery = false,
      platformCount = 1,
      expectedChangedPathCount = 1,
    )
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
    require(mediumMinScore in 0..FEATURE_TASK_RUNTIME_COMPLEXITY_MAX)
    require(largeMinScore in mediumMinScore..FEATURE_TASK_RUNTIME_COMPLEXITY_MAX)
    require(decompositionMinScore in largeMinScore..FEATURE_TASK_RUNTIME_COMPLEXITY_MAX)
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
      signals.moduleBreadth >= CROSS_BOUNDARY_BREADTH_MINIMUM &&
        signals.boundaryBreadth >= CROSS_BOUNDARY_BREADTH_MINIMUM &&
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
    require(overrideId.isNotBlank() && overrideId.length <= ADAPTIVE_ID_MAX_LENGTH)
    require(policyVersion == FEATURE_TASK_RUNTIME_ADAPTIVE_POLICY_CONTRACT_VERSION)
    require(rationale.isNotBlank() && rationale.length <= ADAPTIVE_SUMMARY_MAX_LENGTH)
  }
}

enum class FeatureTaskRuntimePlanAdvance { IMPLEMENT, REENTER_PLAN_FOR_DECOMPOSITION }

object FeatureTaskRuntimeDirectPlanGate {
  fun resolve(
    decision: FeatureTaskRuntimeSizingDecision,
    override: FeatureTaskRuntimeGovernedDirectOverride? = null,
  ): FeatureTaskRuntimePlanAdvance = if (
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
    val riskAreas = adaptiveReviewRiskAreas(signals)
    val depth = minimumAdaptiveReviewDepth(sizing, signals, riskAreas)
    val minimumMode = minimumAdaptiveReviewMode(depth)
    val requested = requestedAdaptiveReviewMode(requestedMode, minimumMode)
    val resolved = if (requested.ordinal < minimumMode.ordinal) minimumMode else requested
    return FeatureTaskRuntimeResolvedReviewPolicy(
      minimumDepth = depth,
      executionMode = resolved,
      requiredSpecialistAreas = riskAreas,
      rationale = listOf("minimum_depth=${depth.name.lowercase()}", "risk_area_count=${riskAreas.size}"),
    )
  }
}

private fun adaptiveReviewRiskAreas(signals: FeatureTaskRuntimeComplexitySignals): Set<String> = buildSet {
  if (signals.persistenceOrMigration) add("persistence")
  if (signals.securityOrPrivacy) add("security")
  if (signals.concurrencyOrLifecycle || signals.processBoundaryOrCrashRecovery) add("platform-correctness")
}

private fun minimumAdaptiveReviewDepth(
  sizing: FeatureTaskRuntimeSizingDecision,
  signals: FeatureTaskRuntimeComplexitySignals,
  riskAreas: Set<String>,
): FeatureTaskRuntimeReviewSubstanceDepth = when {
  riskAreas.size >= MULTI_SPECIALIST_RISK_COUNT ||
    signals.boundaryBreadth >= MULTI_SPECIALIST_BOUNDARY_BREADTH ->
    FeatureTaskRuntimeReviewSubstanceDepth.MULTI_SPECIALIST
  riskAreas.isNotEmpty() || sizing.featureSize == FeatureTaskRuntimeFeatureSize.LARGE ->
    FeatureTaskRuntimeReviewSubstanceDepth.SPECIALIST
  else -> FeatureTaskRuntimeReviewSubstanceDepth.STANDARD
}

private fun minimumAdaptiveReviewMode(
  depth: FeatureTaskRuntimeReviewSubstanceDepth,
): FeatureTaskRuntimeResolvedReviewMode = when (depth) {
  FeatureTaskRuntimeReviewSubstanceDepth.MULTI_SPECIALIST ->
    FeatureTaskRuntimeResolvedReviewMode.PARALLEL_SPECIALIST
  FeatureTaskRuntimeReviewSubstanceDepth.SPECIALIST ->
    FeatureTaskRuntimeResolvedReviewMode.DELEGATED
  FeatureTaskRuntimeReviewSubstanceDepth.STANDARD ->
    FeatureTaskRuntimeResolvedReviewMode.INLINE
}

private fun requestedAdaptiveReviewMode(
  requestedMode: CodeReviewExecutionMode,
  minimumMode: FeatureTaskRuntimeResolvedReviewMode,
): FeatureTaskRuntimeResolvedReviewMode = when (requestedMode) {
  CodeReviewExecutionMode.INLINE -> FeatureTaskRuntimeResolvedReviewMode.INLINE
  CodeReviewExecutionMode.DELEGATED -> FeatureTaskRuntimeResolvedReviewMode.DELEGATED
  CodeReviewExecutionMode.AUTO -> minimumMode
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
    require(checkId.isNotBlank() && checkId.length <= ADAPTIVE_ID_MAX_LENGTH)
    require(ownedPaths.isNotEmpty() && ownedPaths.size <= MAX_CHANGED_PATH_COUNT)
    require(ownedPaths == ownedPaths.distinct().sorted())
    require(ownedPaths.all(::isNormalizedRepositoryPath))
    require(checkerSkill.isNotBlank() && checkerSkill.length <= ADAPTIVE_ID_MAX_LENGTH)
  }
}

data class FeatureTaskRuntimeFocusedQualitySelection(
  val semanticFingerprint: String,
  val checks: List<FeatureTaskRuntimeFocusedQualityCheck>,
)

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
    require(attempt in 1..QUALITY_REPAIR_MAX_ATTEMPTS)
    require(items.isNotEmpty() && items.size <= QUALITY_REPAIR_MAX_ITEMS)
    require(items.map { it.itemId }.distinct().size == items.size)
    require(
      items.all {
        it.boundedDiagnostic.isNotBlank() && it.boundedDiagnostic.length <= ADAPTIVE_SUMMARY_MAX_LENGTH
      },
    )
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
    require(decisionId.isNotBlank() && decisionId.length <= ADAPTIVE_ID_MAX_LENGTH)
  }

  fun withFocusedQuality(outcome: FeatureTaskRuntimeFocusedQualityOutcome): FeatureTaskRuntimeAdaptiveDecisionRecord =
    copy(focusedQualityOutcome = outcome)
}

private fun isNormalizedRepositoryPath(path: String): Boolean = path.isNotBlank() &&
  !path.startsWith("/") &&
  '\\' !in path &&
  path.split('/').none { it.isBlank() || it == "." || it == ".." }
