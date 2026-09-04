package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.infrastructure.fs.ClasspathReviewSpecialistContractProvider
import skillbill.infrastructure.fs.FileExternalAgentAddonSourceConfigStore
import skillbill.infrastructure.fs.FileSystemDecompositionManifestFileStore
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSpecStatusWriter
import skillbill.infrastructure.fs.FileSystemReviewAttribution
import skillbill.infrastructure.fs.FileSystemReviewRubricResolver
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.infrastructure.fs.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.model.WorkflowOpsContext
import skillbill.skillremove.FileSystemSkillRemoveFileSystem

internal interface RuntimeReviewWorkflowProvides {
  @Provides @JvmSynthetic
  fun reviewAttributionPort(adapter: FileSystemReviewAttribution) =
    RuntimeReviewFeatureTaskAgentAddonBindings.reviewAttributionPort(adapter)

  @Provides @JvmSynthetic
  fun reviewRubricResolver(adapter: FileSystemReviewRubricResolver) =
    RuntimeReviewFeatureTaskAgentAddonBindings.reviewRubricResolver(adapter)

  @Provides @JvmSynthetic
  fun reviewSpecialistContractProvider(adapter: ClasspathReviewSpecialistContractProvider) =
    RuntimeReviewFeatureTaskAgentAddonBindings.reviewSpecialistContractProvider(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeRunInvariantsSource(adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource) =
    RuntimeReviewFeatureTaskAgentAddonBindings.featureTaskRuntimeRunInvariantsSource(adapter)

  @Provides @JvmSynthetic
  fun externalAgentAddonSourceConfigPort(store: FileExternalAgentAddonSourceConfigStore) =
    RuntimeReviewFeatureTaskAgentAddonBindings.externalAgentAddonSourceConfigPort(store)

  @Provides @RuntimeSingleton @JvmSynthetic
  fun featureTaskRuntimeWorkerSupervisor(adapter: JdkFeatureTaskRuntimeWorkerSupervisor) =
    RuntimeReviewFeatureTaskAgentAddonBindings.featureTaskRuntimeWorkerSupervisor(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeSpecStatusWriter(adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter) =
    RuntimeReviewFeatureTaskAgentAddonBindings.featureTaskRuntimeSpecStatusWriter(adapter)

  @Provides @JvmSynthetic
  fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem) =
    RuntimeWorkflowInstallStoreBindings.skillRemoveFileSystem(fileSystem)

  @Provides @JvmSynthetic
  fun workflowGitOperations(workflowOps: WorkflowOpsContext, git: GitWorkflowGitOperations) =
    RuntimeWorkflowInstallStoreBindings.workflowGitOperations(workflowOps, git)

  @Provides @JvmSynthetic
  fun decompositionManifestStore(store: FileSystemDecompositionManifestFileStore) =
    RuntimeWorkflowInstallStoreBindings.decompositionManifestStore(store)
}
