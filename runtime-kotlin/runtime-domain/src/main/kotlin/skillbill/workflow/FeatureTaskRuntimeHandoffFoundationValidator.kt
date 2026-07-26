package skillbill.workflow

import skillbill.boundary.OpenBoundaryMap

/** Schema-validation port for the three SKILL-146 foundation wire contracts. */
interface FeatureTaskRuntimeHandoffFoundationValidator {
  @OpenBoundaryMap("Feature-task-runtime phase-handoff declaration schema-validation seam")
  fun validateDeclaration(payload: Map<String, Any?>, sourceLabel: String)

  @OpenBoundaryMap("Feature-task-runtime persistence record schema-validation seam")
  fun validatePersistenceRecord(payload: Map<String, Any?>, sourceLabel: String)

  @OpenBoundaryMap("Feature-task-runtime projection measurement schema-validation seam")
  fun validateMeasurement(payload: Map<String, Any?>, sourceLabel: String)
}
