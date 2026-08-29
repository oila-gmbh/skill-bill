package skillbill.di

import skillbill.agentaddon.AgentAddonSelectionResolver
import skillbill.infrastructure.fs.ClasspathReviewSpecialistContractProvider
import skillbill.infrastructure.fs.FileExternalAgentAddonSourceConfigStore
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeRunInvariantsSource
import skillbill.infrastructure.fs.FileSystemFeatureTaskRuntimeSpecStatusWriter
import skillbill.infrastructure.fs.FileSystemReviewAttribution
import skillbill.infrastructure.fs.FileSystemReviewRubricResolver
import skillbill.infrastructure.fs.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.agentaddon.ExternalAgentAddonSourceConfigPort
import skillbill.ports.review.ReviewAttributionPort
import skillbill.ports.review.ReviewRubricResolver
import skillbill.ports.review.ReviewSpecialistContractProvider
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor

internal object RuntimeComponentBindingsB3 {
  internal fun reviewAttributionPort(adapter: FileSystemReviewAttribution): ReviewAttributionPort = adapter

  internal fun reviewRubricResolver(adapter: FileSystemReviewRubricResolver): ReviewRubricResolver = adapter

  internal fun reviewSpecialistContractProvider(
    adapter: ClasspathReviewSpecialistContractProvider,
  ): ReviewSpecialistContractProvider = adapter

  internal fun featureTaskRuntimeRunInvariantsSource(
    adapter: FileSystemFeatureTaskRuntimeRunInvariantsSource,
  ): FeatureTaskRuntimeRunInvariantsSource = adapter

  fun agentAddonSelectionPort(): AgentAddonSelectionPort = AgentAddonSelectionResolver()

  internal fun externalAgentAddonSourceConfigPort(
    store: FileExternalAgentAddonSourceConfigStore,
  ): ExternalAgentAddonSourceConfigPort = store

  internal fun featureTaskRuntimeWorkerSupervisor(
    adapter: JdkFeatureTaskRuntimeWorkerSupervisor,
  ): FeatureTaskRuntimeWorkerSupervisor = adapter

  internal fun featureTaskRuntimeSpecStatusWriter(
    adapter: FileSystemFeatureTaskRuntimeSpecStatusWriter,
  ): FeatureTaskRuntimeSpecStatusWriter = adapter
}
