package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.GoalPlanningPreparationSchemaValidator
import skillbill.workflow.GoalPlanningPreparationEnvelopeValidator

@Inject
class GoalPlanningPreparationEnvelopeValidatorAdapter : GoalPlanningPreparationEnvelopeValidator {
  override fun validate(envelope: Map<String, Any?>, sourceLabel: String) {
    GoalPlanningPreparationSchemaValidator.validate(envelope, sourceLabel)
  }
}
