package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.model.EnvironmentContext
import skillbill.model.RepositoryRoot
import skillbill.model.WorkflowOpsContext
import skillbill.ports.decomposition.DecompositionManifestProjectionWriter
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations

internal interface RuntimeWorkflowProvides {
  @Provides @JvmSynthetic
  fun repositoryRoot(context: EnvironmentContext): RepositoryRoot = RuntimeBootstrapBindings.repositoryRoot(context)

  @Provides @JvmSynthetic
  fun gitWorkflowGitOperations(): GitWorkflowGitOperations = GitWorkflowGitOperations()

  @Provides @JvmSynthetic
  fun workflowGitOperations(workflowOps: WorkflowOpsContext, git: GitWorkflowGitOperations): WorkflowGitOperations =
    if (workflowOps.workflowGitOperations === NoopWorkflowGitOperations) git else workflowOps.workflowGitOperations

  @Provides @JvmSynthetic
  fun decompositionManifestStore(store: FileSystemDecompositionManifestFileStore): DecompositionManifestStore = store

  @Provides @JvmSynthetic
  fun decompositionManifestProjectionWriter(
    writer: DecompositionManifestWriter,
  ): DecompositionManifestProjectionWriter = writer
}
