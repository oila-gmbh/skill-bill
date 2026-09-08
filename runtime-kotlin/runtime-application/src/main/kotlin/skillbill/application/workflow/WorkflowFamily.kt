package skillbill.application.workflow

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.ports.workflow.persistence.model.WorkflowFamily as WorkflowFamilyModel

typealias WorkflowFamily = WorkflowFamilyModel

fun WorkflowFamilyKind.workflowFamily(): WorkflowFamily = when (this) {
  WorkflowFamilyKind.VERIFY -> WorkflowFamily.VERIFY
  WorkflowFamilyKind.TASK_RUNTIME -> WorkflowFamily.TASK_RUNTIME
}
