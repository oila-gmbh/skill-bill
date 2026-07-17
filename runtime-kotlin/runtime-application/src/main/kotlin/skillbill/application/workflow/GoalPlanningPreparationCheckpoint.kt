package skillbill.application.workflow

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.GoalPlanningPreparationValidator
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.GoalPlanningPreparationEnvelopeValidator

@Inject
class GoalPlanningPreparationCheckpoint(
  private val database: DatabaseSessionFactory,
  private val envelopeValidator: GoalPlanningPreparationEnvelopeValidator,
  phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
) {
  private val preparationValidator = GoalPlanningPreparationValidator(phaseOutputValidator)

  fun checkpoint(record: GoalPlanningPreparationRecord, dbOverride: String? = null) {
    val sourceLabel = "${record.parentGoalWorkflowId}#${record.subtaskId}"
    envelopeValidator.validate(record.toEnvelopeMap(), sourceLabel)
    preparationValidator.validate(record)
    database.read(dbOverride) { unitOfWork -> unitOfWork.goalPlanningPreparations.markPrepared(record) }
  }
}
