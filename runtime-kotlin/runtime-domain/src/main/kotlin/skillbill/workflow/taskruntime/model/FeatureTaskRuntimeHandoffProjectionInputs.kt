package skillbill.workflow.taskruntime.model

import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimePlanningProjectionValidator

/** Everything the validator may read while projecting; no open map, no agent-supplied channel. */
data class FeatureTaskRuntimeHandoffProjectionInputs(
  val consumerPhaseId: String,
  val declarations: List<PhaseHandoffProjectionDeclaration>,
  val resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  /** Freshly resolved repository checkpoint, when the application layer resolved one. */
  val resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  /** Checkpoint recorded in durable state, compared against the resolved one under `must_match`. */
  val expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  /**
   * The shared review evidence the application layer resolved for this launch, or null when none was
   * resolvable. Null omits the non-required declaration rather than delivering it empty; the domain
   * never reaches a filesystem to find one.
   */
  val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference? = null,
  /**
   * The runtime-derived bounded prior-gap memory for an `audit_gap` remediation round, or null when
   * none is derivable (forward launches and in-flight runs that predate the projection). Null omits
   * the non-required declaration rather than delivering it empty.
   */
  val priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val recordedFindingVerdicts: List<ReviewFindingVerdict> = emptyList(),
  /** Runtime-owned branch identity used only by bounded finalization request projectors. */
  val branchIdentity: String? = null,
  val baseBranch: String = "main",
  val addonContentBySlug: Map<String, String> = emptyMap(),
  val workflowId: String? = null,
  /**
   * Goal-continuation validate depth used when projecting [VALIDATION_REQUEST] required_checks.
   * Defaults to [ValidationDepth.FULL] so absent/non-goal launches keep today's merge of plan
   * validation_strategy + task test_obligations. Domain-safe wire enum — not the application
   * GoalContinuationContext type.
   */
  val validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  val qualityGateSelection: FeatureTaskRuntimeQualityGateSelection = FeatureTaskRuntimeQualityGateSelection.VALIDATE,
  /**
   * Canonical planning-projections schema gate, called before a bounded projection is parsed. The
   * default leaves the schema unenforced and exists only for suites asserting the typed Kotlin rules
   * in isolation; production wiring passes the infra-fs-backed adapter.
   */
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
    NoopFeatureTaskRuntimePlanningProjectionValidator,
)

/** Bound on a repository-fingerprint pointer carried in a handoff projection. */
const val MAX_REPOSITORY_FINGERPRINT_LENGTH: Int = 256

/** Bound on a governed pointer — a path, a path:symbol, or an identifier — carried on the wire. */
const val MAX_BOUNDED_POINTER_LENGTH: Int = 256
