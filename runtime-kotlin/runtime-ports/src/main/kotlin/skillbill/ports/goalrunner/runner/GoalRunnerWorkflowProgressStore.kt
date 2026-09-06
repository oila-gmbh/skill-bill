package skillbill.ports.goalrunner.runner

import skillbill.boundary.OpenBoundaryMap
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.engine.model.WorkflowId

interface GoalRunnerWorkflowProgressStore {
  fun progress(workflowId: WorkflowId): GoalRunnerWorkflowProgress?

  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean

  fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean

  @OpenBoundaryMap("Durable goal progress-event artifact maps read back at the goal-runner workflow seam")
  fun progressEvents(workflowId: WorkflowId): List<Map<String, Any?>>
}
