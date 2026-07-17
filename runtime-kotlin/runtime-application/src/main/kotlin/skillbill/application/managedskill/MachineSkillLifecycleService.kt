package skillbill.application.managedskill

import me.tatarka.inject.annotations.Inject
import skillbill.managedskill.model.ConfirmMachineSkillDeleteRequest
import skillbill.managedskill.model.MachineSkillApplyResult
import skillbill.managedskill.model.MachineSkillMutationKind
import skillbill.managedskill.model.MachineSkillOperationPreview
import skillbill.managedskill.model.MachineSkillOperationResult
import skillbill.managedskill.model.MachineSkillOutcome
import skillbill.managedskill.model.MachineSkillServiceOutcome
import skillbill.managedskill.model.MachineSkillServiceOutcomeKind

@Inject
class MachineSkillLifecycleService(private val coordinator: MachineSkillMutationCoordinator) {
  suspend fun apply(preview: MachineSkillOperationPreview): MachineSkillOperationResult {
    val prepared = preview.prepared
      ?: return result(
        preview,
        null,
        preview.outcomes + outcome(preview, MachineSkillServiceOutcomeKind.BLOCKED, "preview-blocked"),
      )
    return when (val applied = coordinator.apply(prepared)) {
      is MachineSkillApplyResult.Applied -> result(preview, applied.planId, outcomes(preview))
      is MachineSkillApplyResult.Stale -> result(
        preview,
        null,
        applied.failures.map { failure ->
          MachineSkillServiceOutcome(
            MachineSkillServiceOutcomeKind.BLOCKED,
            "stale-${failure.code}",
            "Expected ${failure.expected}; found ${failure.actual}",
            preview.skillName,
            path = failure.path,
          )
        },
      )
      is MachineSkillApplyResult.Blocked -> result(
        preview,
        null,
        listOf(outcome(preview, MachineSkillServiceOutcomeKind.FAILED, "apply-failed", applied.reason)),
      )
    }
  }

  suspend fun confirmDelete(request: ConfirmMachineSkillDeleteRequest): MachineSkillOperationResult {
    val preview = request.preview
    if (preview.operation != MachineSkillMutationKind.DELETE) {
      return result(
        preview,
        null,
        listOf(outcome(preview, MachineSkillServiceOutcomeKind.BLOCKED, "confirmation-not-delete")),
      )
    }
    val planId = preview.prepared?.plan?.planId
    if (planId == null || request.confirmedPlanId != planId) {
      return result(
        preview,
        null,
        preview.outcomes + outcome(preview, MachineSkillServiceOutcomeKind.BLOCKED, "confirmation-mismatch"),
      )
    }
    return apply(preview)
  }

  private fun outcomes(preview: MachineSkillOperationPreview): List<MachineSkillServiceOutcome> =
    preview.prepared!!.plan.mutations.map { mutation ->
      val kind = when (mutation.outcome) {
        MachineSkillOutcome.CREATE -> MachineSkillServiceOutcomeKind.CREATED
        MachineSkillOutcome.RETARGET, MachineSkillOutcome.REPLACE -> MachineSkillServiceOutcomeKind.RETARGETED
        MachineSkillOutcome.REMOVE -> MachineSkillServiceOutcomeKind.REMOVED
        MachineSkillOutcome.UNCHANGED -> MachineSkillServiceOutcomeKind.UNCHANGED
        MachineSkillOutcome.CONFLICT -> MachineSkillServiceOutcomeKind.CONFLICT
        MachineSkillOutcome.WARNING -> MachineSkillServiceOutcomeKind.WARNING
        MachineSkillOutcome.SKIPPED -> MachineSkillServiceOutcomeKind.SKIPPED
        MachineSkillOutcome.BLOCKED -> MachineSkillServiceOutcomeKind.BLOCKED
      }
      MachineSkillServiceOutcome(
        kind,
        mutation.outcome.name.lowercase(),
        mutation.operation.name,
        preview.skillName,
        path = mutation.path,
      )
    } + preview.outcomes

  private fun outcome(
    preview: MachineSkillOperationPreview,
    kind: MachineSkillServiceOutcomeKind,
    code: String,
    detail: String = code,
  ) = MachineSkillServiceOutcome(kind, code, detail, preview.skillName)

  private fun result(
    preview: MachineSkillOperationPreview,
    planId: String?,
    outcomes: List<MachineSkillServiceOutcome>,
  ) = MachineSkillOperationResult(preview.operation, preview.skillName, planId, outcomes)
}
