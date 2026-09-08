package skillbill.di

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

internal object RuntimeInstallNativeAgentBindings {
  internal fun installReconcilePort(adapter: FileSystemInstallReconcile): InstallReconcilePort = adapter

  internal fun installReconcileApplyPort(adapter: FileSystemInstallReconcileApply): InstallReconcileApplyPort = adapter

  internal fun baselineManifestPersistencePort(
    adapter: FileSystemBaselineManifestPersistence,
  ): BaselineManifestPersistencePort = adapter

  // SKILL-77 Subtask 4: read-only installed-workspace modified-vs-baseline status,
  // consumed by the desktop tree to flag locally edited skills.

  internal fun installedWorkspaceBaselineStatusPort(
    adapter: FileSystemInstalledWorkspaceBaselineStatus,
  ): InstalledWorkspaceBaselineStatusPort = adapter

  internal fun installSkillLinkPort(adapter: FileSystemInstallSkillLink): InstallSkillLinkPort = adapter

  internal fun installAgentTargetPort(adapter: FileSystemInstallAgentTargets): InstallAgentTargetPort = adapter

  internal fun installNativeAgentLinkPort(adapter: FileSystemInstallNativeAgentLinks): InstallNativeAgentLinkPort =
    adapter

  internal fun installMcpRegistrationPort(adapter: FileSystemInstallMcpRegistration): InstallMcpRegistrationPort =
    adapter
}
