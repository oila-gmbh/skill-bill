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

internal interface RuntimeInstallLauncherProvides {
  @Provides @JvmSynthetic
  fun installReconcilePort(adapter: FileSystemInstallReconcile) =
    RuntimeInstallNativeAgentBindings.installReconcilePort(adapter)

  @Provides @JvmSynthetic
  fun installReconcileApplyPort(adapter: FileSystemInstallReconcileApply) =
    RuntimeInstallNativeAgentBindings.installReconcileApplyPort(adapter)

  @Provides @JvmSynthetic
  fun baselineManifestPersistencePort(adapter: FileSystemBaselineManifestPersistence) =
    RuntimeInstallNativeAgentBindings.baselineManifestPersistencePort(adapter)

  @Provides @JvmSynthetic
  fun installedWorkspaceBaselineStatusPort(adapter: FileSystemInstalledWorkspaceBaselineStatus) =
    RuntimeInstallNativeAgentBindings.installedWorkspaceBaselineStatusPort(adapter)

  @Provides @JvmSynthetic
  fun installSkillLinkPort(adapter: FileSystemInstallSkillLink) =
    RuntimeInstallNativeAgentBindings.installSkillLinkPort(adapter)

  @Provides @JvmSynthetic
  fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets) =
    RuntimeInstallNativeAgentBindings.installAgentTargetPort(adapter)

  @Provides @JvmSynthetic
  fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks) =
    RuntimeInstallNativeAgentBindings.installNativeAgentLinkPort(adapter)

  @Provides @JvmSynthetic
  fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration) =
    RuntimeInstallNativeAgentBindings.installMcpRegistrationPort(adapter)

  @Provides @JvmSynthetic
  fun agentRunLauncher(callbacks: OptionalCallbacks, adapter: FileSystemAgentRunLauncher) =
    RuntimeLauncherGoalRunnerBindings.agentRunLauncher(callbacks, adapter)

  @Provides @JvmSynthetic
  fun goalRunnerSubtaskLauncher(adapter: AgentRunGoalRunnerSubtaskLauncher) =
    RuntimeLauncherGoalRunnerBindings.goalRunnerSubtaskLauncher(adapter)
}
