package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.PortableReviewBaselineValidation
import skillbill.application.goalrunner.model.PortableReviewBaselineValidationRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.workflow.goal.model.PortableReviewBaselineBlockedReason

object PortableReviewBaselineRehydrator {
  fun blockedBaselineResult(blocked: PortableReviewBaselineValidation.Blocked): GoalSubtaskReviewBaselineResult =
    GoalSubtaskReviewBaselineResult(
      status = "error",
      error = "${blocked.reason.wireValue}: ${blocked.detail} ${blocked.reason.recoveryAction}",
    )

  fun rehydrateBaseline(request: PortableReviewBaselineValidationRequest): GoalSubtaskReviewBaselineResult {
    return when (val validation = PortableReviewBaselineValidator.validateStoredArtifact(request)) {
      is PortableReviewBaselineValidation.Valid -> GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = validation.reviewBaseline,
      )
      is PortableReviewBaselineValidation.Blocked -> blockedBaselineResult(validation)
    }
  }

  fun blockedReasonMessage(reason: PortableReviewBaselineBlockedReason, detail: String): String =
    "${reason.wireValue}: $detail ${reason.recoveryAction}"
}
