package skillbill.ports.goalrunner.runner

import skillbill.boundary.OpenBoundaryMap
import skillbill.ports.goalrunner.runner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerProgressEventRecordRequest
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress

interface GoalRunnerWorkflowProgressStore {
  fun progress(workflowId: String): GoalRunnerWorkflowProgress?

  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest): Boolean

  fun recordProgressEvent(request: GoalRunnerProgressEventRecordRequest): Boolean

  @OpenBoundaryMap("Durable goal progress-event artifact maps read back at the goal-runner workflow seam")
  fun progressEvents(workflowId: String): List<Map<String, Any?>>
}
