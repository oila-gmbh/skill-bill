package skillbill.di

import skillbill.infrastructure.fs.DecompositionManifestValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.FileSystemSpecScratchStore
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.infrastructure.fs.InstallPlanWireValidatorAdapter
import skillbill.infrastructure.fs.WorkflowSnapshotValidatorInfraAdapter
import skillbill.install.model.InstallPlanWireValidator
import skillbill.model.WorkflowOpsContext
import skillbill.ports.skillremove.SkillRemoveFileSystem
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.skillremove.FileSystemSkillRemoveFileSystem
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator

internal object RuntimeWorkflowInstallStoreBindings {
  internal fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem): SkillRemoveFileSystem = fileSystem

  internal fun workflowGitOperations(
    workflowOps: WorkflowOpsContext,
    git: GitWorkflowGitOperations,
  ): WorkflowGitOperations =
    if (workflowOps.workflowGitOperations === NoopWorkflowGitOperations) git else workflowOps.workflowGitOperations

  internal fun decompositionManifestStore(
    store: FileSystemDecompositionManifestFileStore,
  ): DecompositionManifestStore = store

  internal fun specScratchStore(store: FileSystemSpecScratchStore): SpecScratchStore = store

  // SKILL-52.3 Subtask 1: validator ports now bind to infra-fs adapters
  // (the module that owns the concrete networknt + Jackson schema
  // validators). `runtime-domain` install policy and the application
  // decomposition + workflow seams reach the validators only through
  // these ports, wired exactly like every other infra adapter above.

  internal fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter): InstallPlanWireValidator = adapter

  internal fun decompositionManifestValidator(
    adapter: DecompositionManifestValidatorAdapter,
  ): DecompositionManifestValidator = adapter

  internal fun workflowSnapshotValidator(adapter: WorkflowSnapshotValidatorInfraAdapter): WorkflowSnapshotValidator =
    adapter

  internal fun featureTaskRuntimePhaseOutputValidator(
    adapter: FeatureTaskRuntimePhaseOutputValidatorAdapter,
  ): FeatureTaskRuntimePhaseOutputValidator = adapter

  // SKILL-137: the canonical planning-projections schema gate. The domain parse seam calls this port
  // before building a typed projection, so the schema is enforced at runtime, not just authored.
}
