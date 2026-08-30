package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.agentrun.AgentRunGoalRunnerSubtaskLauncher
import skillbill.infrastructure.fs.FileSystemBaselineManifestPersistence
import skillbill.infrastructure.fs.FileSystemInstallAgentTargets
import skillbill.infrastructure.fs.FileSystemInstallMcpRegistration
import skillbill.infrastructure.fs.FileSystemInstallNativeAgentLinks
import skillbill.infrastructure.fs.FileSystemInstallReconcile
import skillbill.infrastructure.fs.FileSystemInstallReconcileApply
import skillbill.infrastructure.fs.FileSystemInstallSkillLink
import skillbill.infrastructure.fs.FileSystemInstalledWorkspaceBaselineStatus
import skillbill.launcher.agentrun.FileSystemAgentRunLauncher
import skillbill.model.OptionalCallbacks

internal interface RuntimeComponentProvides2 {
  @Provides @JvmSynthetic
  fun installReconcilePort(adapter: FileSystemInstallReconcile) =
    RuntimeComponentBindingsA3.installReconcilePort(adapter)

  @Provides @JvmSynthetic
  fun installReconcileApplyPort(adapter: FileSystemInstallReconcileApply) =
    RuntimeComponentBindingsA3.installReconcileApplyPort(adapter)

  @Provides @JvmSynthetic
  fun baselineManifestPersistencePort(adapter: FileSystemBaselineManifestPersistence) =
    RuntimeComponentBindingsA3.baselineManifestPersistencePort(adapter)

  @Provides @JvmSynthetic
  fun installedWorkspaceBaselineStatusPort(adapter: FileSystemInstalledWorkspaceBaselineStatus) =
    RuntimeComponentBindingsA3.installedWorkspaceBaselineStatusPort(adapter)

  @Provides @JvmSynthetic
  fun installSkillLinkPort(adapter: FileSystemInstallSkillLink) =
    RuntimeComponentBindingsA3.installSkillLinkPort(adapter)

  @Provides @JvmSynthetic
  fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets) =
    RuntimeComponentBindingsA3.installAgentTargetPort(adapter)

  @Provides @JvmSynthetic
  fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks) =
    RuntimeComponentBindingsA3.installNativeAgentLinkPort(adapter)

  @Provides @JvmSynthetic
  fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration) =
    RuntimeComponentBindingsA3.installMcpRegistrationPort(adapter)

  @Provides @JvmSynthetic
  fun agentRunLauncher(callbacks: OptionalCallbacks, adapter: FileSystemAgentRunLauncher) =
    RuntimeComponentBindingsA4.agentRunLauncher(callbacks, adapter)

  @Provides @JvmSynthetic
  fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher) =
    RuntimeComponentBindingsA4.goalRunnerSubtaskLauncher(adapter)
}
