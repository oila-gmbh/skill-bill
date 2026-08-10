package skillbill.ports.validation

import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult

/** Runs a pack-declared validation gate argv in the repository root. */
interface ValidationGateRunner {
  fun run(request: ValidationGateRunRequest): ValidationGateRunResult
}
