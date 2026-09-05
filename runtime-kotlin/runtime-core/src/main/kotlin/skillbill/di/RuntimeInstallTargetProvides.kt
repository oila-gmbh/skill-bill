package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemBaselineManifestPersistence
import skillbill.infrastructure.fs.FileSystemInstallAgentTargets
import skillbill.infrastructure.fs.FileSystemInstallMcpRegistration
import skillbill.infrastructure.fs.FileSystemInstallNativeAgentLinks
import skillbill.infrastructure.fs.FileSystemInstallReconcile
import skillbill.infrastructure.fs.FileSystemInstallReconcileApply
import skillbill.infrastructure.fs.FileSystemInstallSkillLink
import skillbill.infrastructure.fs.FileSystemInstalledWorkspaceBaselineStatus
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.reconcile.InstallReconcileApplyPort
import skillbill.ports.install.reconcile.InstallReconcilePort

internal interface RuntimeInstallTargetProvides {
  @Provides @JvmSynthetic
  fun installReconcilePort(adapter: FileSystemInstallReconcile): InstallReconcilePort = adapter

  @Provides @JvmSynthetic
  fun installReconcileApplyPort(adapter: FileSystemInstallReconcileApply): InstallReconcileApplyPort = adapter

  @Provides @JvmSynthetic
  fun baselineManifestPersistencePort(adapter: FileSystemBaselineManifestPersistence): BaselineManifestPersistencePort =
    adapter

  @Provides @JvmSynthetic
  fun installedWorkspaceBaselineStatusPort(
    adapter: FileSystemInstalledWorkspaceBaselineStatus,
  ): InstalledWorkspaceBaselineStatusPort = adapter

  @Provides @JvmSynthetic
  fun installSkillLinkPort(adapter: FileSystemInstallSkillLink): InstallSkillLinkPort = adapter

  @Provides @JvmSynthetic
  fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets): InstallAgentTargetPort = adapter

  @Provides @JvmSynthetic
  fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks): InstallNativeAgentLinkPort = adapter

  @Provides @JvmSynthetic
  fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration): InstallMcpRegistrationPort = adapter
}
