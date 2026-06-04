package skillbill.application

import me.tatarka.inject.annotations.Inject

/**
 * Bundles the phase-transition collaborators the runtime consults at specific run boundaries: the
 * branch-setup gate before the first file-mutating phase, the plan-phase stop decision, and the
 * runtime-owned lifecycle telemetry seam. Grouping them keeps the runner's dependency surface small.
 */
@Inject
class FeatureTaskRuntimePhaseGates(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
)
