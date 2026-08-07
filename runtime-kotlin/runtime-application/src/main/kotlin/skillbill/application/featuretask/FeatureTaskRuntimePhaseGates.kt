package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator

@Suppress("LongParameterList") // one gate seam; every parameter is a mandatory gate dependency
@Inject
class FeatureTaskRuntimePhaseGates(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val gitOperations: WorkflowGitOperations,
  val specGate: FeatureTaskRuntimeSpecGate,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  // The checkpoint-keyed shared review evidence seam. Defaulted so a suite constructing the gates
  // directly gets the pre-store behaviour (derive in line, persist nothing) rather than a new argument.
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  val diffResolver: DiffResolverPort = UnreadableDiffResolver,
)

/** No repository access at all: every derivation reports the diff as unreadable and yields no evidence. */
private object UnreadableDiffResolver : DiffResolverPort {
  override fun runProcess(args: List<String>, workDir: java.nio.file.Path): String? = null
}
