package skillbill.application

import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator

/**
 * The production Draft 2020-12 planning-projection validator, exposed to runtime-application tests that
 * must prove behavior against the enforced schema rather than the Noop stand-in. Lives in testFixtures
 * because only that source set may reach the infra-fs adapter; production application code depends on
 * the port alone.
 */
val realPlanningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
  FeatureTaskRuntimePlanningProjectionValidatorAdapter()
