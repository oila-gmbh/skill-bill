package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidWorkflowStateSchemaError

/**
 * SKILL-52.2 Subtask 4: domain-owned validator port for the canonical
 * workflow-state snapshot map.
 *
 * The `runtime-domain` workflow engine MUST NOT import the runtime
 * contracts schema validator (or any other `skillbill.contracts.*`
 * schema validator) directly — that responsibility now lives at the
 * application boundary, which constructs the validator implementation
 * and threads it into the engine.
 *
 * Implementations MUST throw
 * [skillbill.error.InvalidWorkflowStateSchemaError] on any schema
 * violation. This keeps the existing loud-fail contract intact at every
 * documented seam:
 *
 *  - `WorkflowEngine.openRecord` / `updateRecord` (in-process
 *    construction)
 *  - `WorkflowEngine.snapshotView` / `summaryView` / `resumeView`
 *    (durable-record read)
 *  - `WorkflowEngine.continueDecision` (chained via [resumeView])
 *
 * The `slug` argument is the snapshot's `workflow_name` (e.g.
 * `bill-feature-task`); implementations should weave it into the
 * loud-fail message so per-skill regressions stay easy to spot.
 */
interface WorkflowSnapshotValidator {
  /**
   * Validates the snapshot-shaped map against the canonical schema.
   *
   * On any violation, throws [InvalidWorkflowStateSchemaError] whose
   * message names the offending field path. The typed
   * `Map<String, Any?>` signature (vs a raw `Any?`) keeps "is it a
   * mapping?" a compile-time concern of the caller — the engine's parse
   * seams already produce a `LinkedHashMap<String, Any?>` by
   * construction.
   */
  @OpenBoundaryMap("Canonical workflow-state snapshot map at the schema-validation seam")
  fun validate(snapshot: Map<String, Any?>, slug: String)
}
