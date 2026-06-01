package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.GoalObservabilityEventSchemaValidator
import skillbill.workflow.GoalObservabilityEventValidator

@Inject
class GoalObservabilityEventValidatorAdapter : GoalObservabilityEventValidator {
  override fun validate(event: Map<String, Any?>, sourceLabel: String) {
    GoalObservabilityEventSchemaValidator.validate(event, sourceLabel)
  }
}
