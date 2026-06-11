package skillbill.desktop.core.data.di

import me.tatarka.inject.annotations.Provides
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.service.JvmDesktopFirstRunGateway
import skillbill.desktop.core.data.service.JvmInstalledWorkspaceBaselineService
import skillbill.desktop.core.data.service.JvmInstalledWorkspaceGitProvisioner
import skillbill.desktop.core.data.service.JvmInstalledWorkspaceLocator
import skillbill.desktop.core.data.service.JvmRuntimeScaffoldGateway
import skillbill.desktop.core.data.service.JvmRuntimeSkillRemoveGateway
import skillbill.desktop.core.data.service.RuntimeGitGateway
import skillbill.desktop.core.data.service.RuntimePrPublishingGateway
import skillbill.desktop.core.data.service.RuntimeRepoBrowserService
import skillbill.desktop.core.domain.service.AuthoringGateway
import skillbill.desktop.core.domain.service.DesktopFirstRunGateway
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.desktop.core.domain.service.InstalledWorkspaceBaselineService
import skillbill.desktop.core.domain.service.InstalledWorkspaceGitProvisioner
import skillbill.desktop.core.domain.service.InstalledWorkspaceLocator
import skillbill.desktop.core.domain.service.PrPublishingGateway
import skillbill.desktop.core.domain.service.RenderGateway
import skillbill.desktop.core.domain.service.RepoSessionService
import skillbill.desktop.core.domain.service.RuntimeScaffoldGateway
import skillbill.desktop.core.domain.service.RuntimeSkillRemoveGateway
import skillbill.desktop.core.domain.service.SkillTreeService
import skillbill.desktop.core.domain.service.ValidationGateway
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(UserScope::class)
interface JvmDataBindings {
  @Provides
  fun RuntimeRepoBrowserService.bindRepoSessionService(): RepoSessionService = this

  @Provides
  fun RuntimeRepoBrowserService.bindSkillTreeService(): SkillTreeService = this

  @Provides
  fun RuntimeRepoBrowserService.bindAuthoringGateway(): AuthoringGateway = this

  @Provides
  fun RuntimeGitGateway.bindGitGateway(): GitGateway = this

  @Provides
  fun RuntimePrPublishingGateway.bindPrPublishingGateway(): PrPublishingGateway = this

  @Provides
  fun RuntimeRepoBrowserService.bindValidationGateway(): ValidationGateway = this

  @Provides
  fun RuntimeRepoBrowserService.bindRenderGateway(): RenderGateway = this

  @Provides
  fun JvmRuntimeScaffoldGateway.bindRuntimeScaffoldGateway(): RuntimeScaffoldGateway = this

  @Provides
  fun JvmRuntimeSkillRemoveGateway.bindRuntimeSkillRemoveGateway(): RuntimeSkillRemoveGateway = this

  @Provides
  fun JvmDesktopFirstRunGateway.bindDesktopFirstRunGateway(): DesktopFirstRunGateway = this

  @Provides
  fun JvmInstalledWorkspaceLocator.bindInstalledWorkspaceLocator(): InstalledWorkspaceLocator = this

  @Provides
  fun JvmInstalledWorkspaceGitProvisioner.bindInstalledWorkspaceGitProvisioner(): InstalledWorkspaceGitProvisioner =
    this

  @Provides
  fun JvmInstalledWorkspaceBaselineService.bindInstalledWorkspaceBaselineService(): InstalledWorkspaceBaselineService =
    this
}
