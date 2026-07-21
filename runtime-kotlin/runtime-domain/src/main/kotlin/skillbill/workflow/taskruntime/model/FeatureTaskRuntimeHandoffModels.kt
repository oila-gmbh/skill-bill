package skillbill.workflow.taskruntime.model

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.model.CodeReviewExecutionMode

/**
 * Domain models for the inter-phase handoff contract. The handoff is an
 * accumulating, schema-validated artifact store over the phase DAG — not a baton
 * passed from phase N-1 to phase N. A phase pulls its declared upstream dependency
 * set, always receives the run-invariants, and optionally receives declared derived
 * context, all design-time properties the executing agent cannot change at runtime.
 */

/**
 * Layer 1 of the handoff: run-invariants injected into every phase. Every field is
 * required and non-null with no omitting factory, so a missing run-invariant fails
 * loudly at construction rather than being silently dropped.
 */
data class FeatureTaskRuntimeRunInvariants(
  /** Reference (path) to the governed spec the run implements. */
  val specReference: String,
  /** Resolved run size. Fixed once at run creation and injected into every phase. */
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.DEFAULT,
  /** Ordered acceptance criteria the run must satisfy. Must be non-empty. */
  val acceptanceCriteria: List<String>,
  /** Mandates and overrides that must reach every phase verbatim. */
  val mandatesAndOverrides: List<String>,
  /** Immutable requested review policy, carried to every initial and repeat review launch. */
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  /** Ordered immutable add-on identities. Content is verified and hydrated before every launch. */
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
) {
  init {
    require(specReference.isNotBlank()) {
      "FeatureTaskRuntimeRunInvariants.specReference must be a non-blank spec reference; " +
        "run-invariants cannot be partially specified."
    }
    require(acceptanceCriteria.isNotEmpty()) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria must list at least one criterion; " +
        "a run with no acceptance criteria has no contract to satisfy."
    }
    require(acceptanceCriteria.none(String::isBlank)) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria must not contain blank entries."
    }
    require(acceptanceCriteria.size <= MAX_ACCEPTANCE_CRITERION_ORDINAL) {
      "FeatureTaskRuntimeRunInvariants.acceptanceCriteria supports at most " +
        "$MAX_ACCEPTANCE_CRITERION_ORDINAL criteria, had ${acceptanceCriteria.size}."
    }
  }
}

/**
 * Feature-task-runtime ceremony size. When governed inputs omit an explicit size,
 * the runtime defaults to [MEDIUM]: the full standard-flow ceremony without large-feature
 * decomposition behavior, keeping the default conservative and deterministic.
 */
enum class FeatureTaskRuntimeFeatureSize {
  SMALL,
  MEDIUM,
  LARGE,
  ;

  companion object {
    val DEFAULT: FeatureTaskRuntimeFeatureSize = MEDIUM

    fun fromWire(value: String): FeatureTaskRuntimeFeatureSize =
      entries.firstOrNull { it.name == value.trim().uppercase() }
        ?: throw IllegalArgumentException(
          "Unknown feature-task-runtime feature size '$value'. Allowed: ${entries.joinToString { it.name }}.",
        )
  }
}

enum class FeatureTaskRuntimePreplanCeremony(val wireValue: String, val promptLabel: String) {
  LIGHT("light", "lighter preplan focused on the current unit of work"),
  FULL("full", "full preplan covering boundaries, risks, rollout, and unknowns"),
}

enum class FeatureTaskRuntimeReviewScope(val wireValue: String, val promptLabel: String) {
  CURRENT_UNIT_OF_WORK("current_unit_of_work", "current-unit-of-work review scope"),
  BRANCH_DIFF("branch_diff", "branch-diff review scope"),
}

enum class FeatureTaskRuntimeAuditCeremony(val wireValue: String, val promptLabel: String) {
  LIGHT("light", "lighter audit over the current unit of work and every listed criterion"),
  FULL_PER_CRITERION("full_per_criterion", "full per-criterion completeness audit"),
}

data class FeatureTaskRuntimeCeremonyScaling(
  val preplanCeremony: FeatureTaskRuntimePreplanCeremony,
  val reviewScope: FeatureTaskRuntimeReviewScope,
  val auditCeremony: FeatureTaskRuntimeAuditCeremony,
) {
  fun toBriefingLines(): List<String> = listOf(
    "preplan_ceremony: ${preplanCeremony.wireValue} (${preplanCeremony.promptLabel})",
    "review_scope: ${reviewScope.wireValue} (${reviewScope.promptLabel})",
    "audit_ceremony: ${auditCeremony.wireValue} (${auditCeremony.promptLabel})",
  )
}

/**
 * One recorded output of a phase. A phase may produce multiple outputs over a
 * run when fix loops re-run it; each carries a monotonically increasing
 * [iteration] so the latest can be selected deterministically.
 */
data class FeatureTaskRuntimePhaseOutput(
  /** Id of the phase that produced this output. */
  val phaseId: String,
  /** 1-based attempt/iteration index; higher means more recent. */
  val iteration: Int,
  /** The validated phase output payload (JSON/YAML text), forwarded downstream. */
  val payload: String,
  /** Canonical output produced by the single validation seam. */
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseOutput.phaseId must be non-blank." }
    require(iteration >= 1) { "FeatureTaskRuntimePhaseOutput.iteration must be >= 1, was $iteration." }
  }
}

/**
 * Layer 2 of the handoff, resolved for one consuming phase: the latest
 * iteration of each statically-declared upstream dependency, keyed by producing
 * phase id.
 */
data class FeatureTaskRuntimeResolvedUpstreamOutputs(
  val outputsByPhaseId: Map<String, FeatureTaskRuntimePhaseOutput>,
)

data class NormalizedFeatureTaskRuntimePhaseOutput(
  val canonicalJson: String,
  @OpenBoundaryMap("Canonical validated phase-output envelope")
  val envelope: Map<String, Any?>,
)

/**
 * The fully-assembled three-layer briefing handed to a single phase: the
 * always-present run-invariants (layer 1), the resolved latest upstream outputs
 * (layer 2), and the statically-declared derived-context keys (layer 3).
 */
data class FeatureTaskRuntimePhaseHandoff(
  val phaseId: String,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  val upstreamOutputs: FeatureTaskRuntimeResolvedUpstreamOutputs,
  val derivedContextKeys: List<String>,
  /**
   * The driving verdict for a backward-edge re-entry (e.g. the review findings for an
   * `implement_fix` re-entry); null for an ordinary forward launch. Upstream outputs are still
   * resolved by the contract, never selected by the re-entered agent.
   */
  val drivingVerdict: FeatureTaskRuntimeVerdict? = null,
  /**
   * The failing acceptance criteria scoping an `audit_gap` implementation re-entry. The remediation
   * addresses only these gaps rather than redoing settled content; empty for a forward launch
   * or a non-audit-gap re-entry, preserving the existing forward-launch assembly.
   */
  val reentryGapCriteria: List<String> = emptyList(),
  val auditRepairPlan: FeatureTaskRuntimeAuditRepairPlan? = null,
  val auditRepairState: FeatureTaskRuntimeAuditRepairState? = null,
  /**
   * Canonical refs of the acceptance criteria already durably closed by a satisfied audit verdict.
   * Carried only for the audit phase, which narrows its verified set to the remaining criteria; every
   * other phase receives an empty list and its briefing is unchanged.
   */
  val durablyClosedCriterionRefs: List<String> = emptyList(),
)

/**
 * Static, design-time declaration for a single phase. Both the consumed
 * upstream outputs and the derived context are fixed in the definition; there
 * is intentionally no API that lets a running agent add to or choose these.
 */
data class FeatureTaskRuntimePhaseDeclaration(
  val phaseId: String,
  /** Producing-phase ids whose latest output this phase consumes. */
  val consumedUpstreamPhaseIds: List<String>,
  /** Statically-declared derived-context keys (e.g. "diff" for `review`). */
  val derivedContextKeys: List<String>,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseDeclaration.phaseId must be non-blank." }
  }
}
