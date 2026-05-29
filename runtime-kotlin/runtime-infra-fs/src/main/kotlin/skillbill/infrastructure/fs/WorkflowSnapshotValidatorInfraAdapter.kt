package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.CanonicalWorkflowStateSchemaValidator
import skillbill.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.workflow.WorkflowSnapshotValidator

/**
 * SKILL-52.3 Subtask 1: infra-side adapter that bridges the domain-owned
 * [WorkflowSnapshotValidator] port to the concrete
 * [CanonicalWorkflowStateSchemaValidator] (now owned by
 * `runtime-infra-fs`).
 *
 * Supersedes the former `runtime-application` `WorkflowSnapshotValidatorAdapter`,
 * which could only construct the validator while it lived in
 * `runtime-contracts`. Now that all three schema validators are owned by
 * `runtime-infra-fs`, every validator port binds to an infra-fs adapter
 * wired through `RuntimeComponent`, keeping the wiring uniform. The wrapped
 * validator caches its compiled JSON Schema instance, so a single shared
 * adapter amortises schema parse + compile cost across every engine call.
 */
@Inject
class WorkflowSnapshotValidatorInfraAdapter : WorkflowSnapshotValidator {
  private val delegate: WorkflowStateSchemaValidator = CanonicalWorkflowStateSchemaValidator()

  override fun validate(snapshot: Map<String, Any?>, slug: String) {
    delegate.validate(snapshot, slug)
  }
}
