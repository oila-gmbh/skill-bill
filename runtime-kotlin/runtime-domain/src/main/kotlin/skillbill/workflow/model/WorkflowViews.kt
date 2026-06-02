package skillbill.workflow.model

import skillbill.boundary.OpenBoundaryMap

/**
 * SKILL-52.1 — Typed view models replacing the raw `Map<String, Any?>`
 * payloads previously returned by `WorkflowEngine.fullPayload` /
 * `summaryPayload` / `resumePayload` / `continueDecision`.
 *
 * These views are the engine's public surface. Adapters at the
 * application/CLI/MCP layer consume them directly. Wire-shape ordered
 * maps are produced by the engine's open-boundary serializer helpers
 * (`WorkflowEngine.snapshotMap` etc.).
 */
data class WorkflowSnapshotView(
  val workflowId: String,
  val sessionId: String,
  val workflowName: String,
  val contractVersion: String,
  val workflowStatus: String,
  val currentStepId: String,
  val steps: List<WorkflowStepState>,
  /**
   * Durable artifacts payload. The artifacts map is intentionally an
   * open-boundary `Map<String, Any?>` — it carries arbitrary JSON
   * objects supplied by callers (preplan_digest, plan, branch, etc.)
   * that have no typed schema beyond the workflow-state schema.
   */
  @OpenBoundaryMap("Durable workflow artifacts payload (caller-supplied JSON)")
  val artifacts: Map<String, Any?>,
  val startedAt: String,
  val updatedAt: String,
  val finishedAt: String,
)

data class WorkflowSummaryView(
  val workflowId: String,
  val sessionId: String,
  val workflowName: String,
  val contractVersion: String,
  val workflowStatus: String,
  val currentStepId: String,
  val startedAt: String,
  val updatedAt: String,
  val finishedAt: String,
)

data class WorkflowUpdateAcknowledgementView(
  val status: String,
  val workflowId: String,
  val workflowName: String,
  val workflowStatus: String,
  val currentStepId: String,
  val updatedStepIds: List<String>,
  val updatedArtifactKeys: List<String>,
  val readOnlyFullStateGuidance: String,
)

data class WorkflowResumeView(
  val snapshot: WorkflowSnapshotView,
  val resumeMode: String,
  val resumeStepId: String,
  val lastCompletedStepId: String,
  val availableArtifacts: List<String>,
  val requiredArtifacts: List<String>,
  val missingArtifacts: List<String>,
  val canResume: Boolean,
  val nextAction: String,
)

data class WorkflowContinuationArtifactSummary(
  val key: String,
  val present: Boolean,
  val inline: Boolean,
  val sizeBytes: Int?,
  /**
   * Caller-supplied artifact JSON when the compact continuation payload can
   * inline it. This is intentionally open-boundary because artifact schemas
   * are owned by workflow callers, not this projection model.
   */
  @OpenBoundaryMap("Inline compact continuation artifact value (caller-supplied JSON)")
  val value: Any?,
  val preview: String?,
  val truncated: Boolean,
  val omitted: Boolean,
  val omissionReason: String?,
)

data class WorkflowCompactContinueView(
  val workflowId: String,
  val skillName: String,
  val continueStatus: String,
  val workflowStatusBeforeContinue: String,
  val startedAt: String,
  val updatedAt: String,
  val resumeStepId: String,
  val resumeStepLabel: String,
  val continueStepDirective: String,
  val referenceSections: List<String>,
  val requiredArtifactKeys: List<String>,
  val availableArtifactKeys: List<String>,
  val missingArtifactKeys: List<String>,
  val currentStepArtifacts: List<WorkflowContinuationArtifactSummary>,
  val omittedArtifactKeys: List<String>,
  val continuationBrief: String,
  val continuationEntryPrompt: String,
  val readOnlyFullStateGuidance: String,
)

data class WorkflowContinueView(
  val resume: WorkflowResumeView,
  val skillName: String,
  val workflowStatusBeforeContinue: String,
  val continueStatus: String,
  val continueStepId: String,
  val continueStepLabel: String,
  val continueStepDirective: String,
  val referenceSections: List<String>,
  val stepArtifactKeys: List<String>,
  /**
   * Recovered durable artifact values keyed by artifact name; the
   * payload mirrors the artifacts map and is an open-boundary
   * passthrough.
   */
  @OpenBoundaryMap("Recovered artifact passthrough for the continue payload")
  val stepArtifacts: Map<String, Any?>,
  /**
   * Implement-flavour extra fields (`feature_name`, `feature_size`,
   * `branch_name`). Open-boundary because the set differs per
   * workflow family and the values are scraped from caller-supplied
   * artifacts.
   */
  @OpenBoundaryMap("Per-workflow-family extra continuation fields")
  val extraFields: Map<String, Any?>,
  /**
   * Workflow-family session summary. Sourced from the durable record
   * via the workflow-state repository.
   */
  @OpenBoundaryMap("Durable workflow session summary passthrough")
  val sessionSummary: Map<String, Any?>,
  val continuationBrief: String,
  val continuationEntryPrompt: String,
  val compact: WorkflowCompactContinueView,
)
