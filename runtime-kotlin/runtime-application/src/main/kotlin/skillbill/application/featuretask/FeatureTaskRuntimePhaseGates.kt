package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.application.featuretask.validation.ValidationGateResolver
import skillbill.application.review.SpecIntentProjectionResolver
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import java.nio.file.Path

@Suppress("LongParameterList") // one gate seam; every parameter is a mandatory gate dependency
@Inject
class FeatureTaskRuntimePhaseGates(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val gitOperations: WorkflowGitOperations,
  val specGate: FeatureTaskRuntimeSpecGate,
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
  val buildReceiptValidator: FeatureTaskRuntimeBuildReceiptValidator,
  val validationGateResolver: ValidationGateResolver,
  val validationGateRunner: ValidationGateRunner,
  val validationGateCoordinator: FeatureTaskRuntimeValidationGateCoordinator,
  val buildGateCoordinator: FeatureTaskRuntimeBuildGateCoordinator,
  // The checkpoint-keyed shared review evidence seam. Defaulted so a suite constructing the gates
  // directly gets the pre-store behaviour (derive in line, persist nothing) rather than a new argument.
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  val diffResolver: DiffResolverPort = UnreadableDiffResolver,
  val reviewDriver: FeatureTaskRuntimeReviewDriver = FeatureTaskRuntimeReviewDriver.EMPTY,
  val specIntentProjectionResolver: SpecIntentProjectionResolver,
  val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory,
)

/** No repository access at all: every derivation reports the diff as unreadable and yields no evidence. */
private object UnreadableDiffResolver : DiffResolverPort {
  override fun runProcess(args: List<String>, workDir: Path): String? = null
}
