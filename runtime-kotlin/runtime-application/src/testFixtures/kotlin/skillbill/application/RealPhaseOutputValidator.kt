package skillbill.application

import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator

/**
 * The production phase-output validator exposed through the application test-fixture seam.
 *
 * Application tests depend on the domain port; only this fixture binds that port to the
 * infrastructure adapter for tests that need the enforced structural-repair and schema behavior.
 */
val realFeatureTaskRuntimePhaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator =
  FeatureTaskRuntimePhaseOutputValidatorAdapter()
