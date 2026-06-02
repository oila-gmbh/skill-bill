package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.GoalProgressEventSchemaValidator
import skillbill.workflow.GoalProgressEventValidator

@Inject
class GoalProgressEventValidatorAdapter : GoalProgressEventValidator {
  override fun validate(event: Map<String, Any?>, sourceLabel: String) {
    GoalProgressEventSchemaValidator.validate(event, sourceLabel)
  }
}
