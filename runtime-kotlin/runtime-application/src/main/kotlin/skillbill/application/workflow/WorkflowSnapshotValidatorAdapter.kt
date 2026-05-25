package skillbill.application.workflow

import skillbill.contracts.workflow.CanonicalWorkflowStateSchemaValidator
import skillbill.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.workflow.WorkflowSnapshotValidator

/**
 * SKILL-52.2 Subtask 4: application-side adapter that bridges the
 * domain-owned [WorkflowSnapshotValidator] port to the runtime contracts
 * [WorkflowStateSchemaValidator] implementation. The application is the
 * boundary that owns this wiring so `runtime-domain` does not import
 * any `skillbill.contracts.*` schema validator directly.
 *
 * The wrapped validator caches its compiled JSON Schema instance, so a
 * single shared adapter amortises schema parse + compile cost across
 * every engine call. Loud-fail behavior is unchanged: the underlying
 * validator throws [skillbill.error.InvalidWorkflowStateSchemaError] on
 * any schema violation, naming the offending field path.
 */
class WorkflowSnapshotValidatorAdapter(
  private val delegate: WorkflowStateSchemaValidator = CanonicalWorkflowStateSchemaValidator(),
) : WorkflowSnapshotValidator {
  override fun validate(snapshot: Map<String, Any?>, slug: String) {
    delegate.validate(snapshot, slug)
  }
}
