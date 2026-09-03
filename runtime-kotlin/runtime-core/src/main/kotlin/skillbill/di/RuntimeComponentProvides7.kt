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
import skillbill.infrastructure.fs.FileSystemSkillRemoveFileSystem
import skillbill.infrastructure.fs.GitWorkflowGitOperations
import skillbill.infrastructure.fs.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.model.WorkflowOpsContext

internal interface RuntimeComponentProvides7 {
  @Provides @JvmSynthetic
  fun reviewAttributionPort(adapter: FileSystemReviewAttribution) =
    RuntimeComponentBindingsB3.reviewAttributionPort(adapter)

  @Provides @JvmSynthetic
  fun reviewRubricResolver(adapter: FileSystemReviewRubricResolver) =
    RuntimeComponentBindingsB3.reviewRubricResolver(adapter)

  @Provides @JvmSynthetic
  fun reviewSpecialistContractProvider(adapter: ClasspathReviewSpecialistContractProvider) =
    RuntimeComponentBindingsB3.reviewSpecialistContractProvider(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeRunInvariantsSource(adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource) =
    RuntimeComponentBindingsB3.featureTaskRuntimeRunInvariantsSource(adapter)

  @Provides @JvmSynthetic
  fun externalAgentAddonSourceConfigPort(store: FileExternalAgentAddonSourceConfigStore) =
    RuntimeComponentBindingsB3.externalAgentAddonSourceConfigPort(store)

  @Provides @RuntimeSingleton @JvmSynthetic
  fun featureTaskRuntimeWorkerSupervisor(adapter: JdkFeatureTaskRuntimeWorkerSupervisor) =
    RuntimeComponentBindingsB3.featureTaskRuntimeWorkerSupervisor(adapter)

  @Provides @JvmSynthetic
  fun featureTaskRuntimeSpecStatusWriter(adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter) =
    RuntimeComponentBindingsB3.featureTaskRuntimeSpecStatusWriter(adapter)

  @Provides @JvmSynthetic
  fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem) =
    RuntimeComponentBindingsB4.skillRemoveFileSystem(fileSystem)

  @Provides @JvmSynthetic
  fun workflowGitOperations(workflowOps: WorkflowOpsContext, git: GitWorkflowGitOperations) =
    RuntimeComponentBindingsB4.workflowGitOperations(workflowOps, git)

  @Provides @JvmSynthetic
  fun decompositionManifestStore(store: FileSystemDecompositionManifestFileStore) =
    RuntimeComponentBindingsB4.decompositionManifestStore(store)
}
