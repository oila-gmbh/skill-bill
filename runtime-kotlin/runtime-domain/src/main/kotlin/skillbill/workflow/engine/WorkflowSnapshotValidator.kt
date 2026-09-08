package skillbill.workflow.engine

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowStateSnapshot

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
 * The `slug` argument is the snapshot's `workflow_name`; implementations should weave it into the
 * loud-fail message so per-skill regressions stay easy to spot.
 *
 * The port takes the typed [WorkflowStateSnapshot]. Projecting it onto the canonical wire shape is
 * the adapter's job, so the engine never builds a snapshot map.
 */
interface WorkflowSnapshotValidator {
  /**
   * Validates the durable snapshot against the canonical schema.
   *
   * On any violation, throws [InvalidWorkflowStateSchemaError] whose message names the offending
   * field path.
   */
  fun validate(snapshot: WorkflowStateSnapshot, slug: String)
}
